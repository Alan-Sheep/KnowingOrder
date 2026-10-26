import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, open, readFile, unlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

// 默认仅校验语料；显式导入时走既有Session/CSRF和真实文档链路，不清库、不调用模型。
const corpus = JSON.parse(await readFile(new URL('../../backend/demo/school-knowledge.json', import.meta.url), 'utf8'));
const hash = value => createHash('sha256').update(value).digest('hex');
// 导入记录只校验手工维护的基础语料；千级演示语料可重新生成，逐篇摘要另行校验。
const baseDigest = hash(JSON.stringify(corpus));
assert.equal(corpus.schoolName, '知序大学');
assert.equal(corpus.spaces.length, 6);
assert.equal(new Set(corpus.spaces.map(space => space.name)).size, 6);
const baseDocuments = corpus.spaces.flatMap(space => space.documents);
assert.equal(baseDocuments.length, 18);
// 可选：合并千级演示语料（generate-school-corpus.mjs 生成到 frontend/.tmp），用于规模演示。
let bulk = { spaces: [] };
try { bulk = JSON.parse(await readFile(new URL('../.tmp/school-corpus-bulk.json', import.meta.url), 'utf8')); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
for (const space of bulk.spaces ?? []) {
  const target = corpus.spaces.find(item => item.name === space.name);
  assert.ok(target, `千级语料包含未知空间：${space.name}`);
  target.documents.push(...space.documents);
}
const documents = corpus.spaces.flatMap(space => space.documents);
assert.ok(new Set(documents.map(doc => doc.title)).size === documents.length, '语料标题重复');
const textOf = doc => `# ${doc.title}\n\n${corpus.edition}\n\n${doc.sections.join('\n\n')}\n\n说明：${corpus.notice}\n`;
for (const doc of documents) {
  assert.ok(doc.title.length <= 128 && doc.sections.length >= 5);
  assert.ok(Buffer.byteLength(textOf(doc), 'utf8') <= 1024 * 1024);
  assert.ok(!/office-live|PRIVATE_POLICY|org-test|联调测试/.test(textOf(doc)));
}
console.log(`学校语料校验通过：${corpus.schoolName}，6个知识空间，${documents.length}篇资料，6个部门；未连接数据库。`);
if (!process.argv.includes('--import')) process.exit(0);
const enterprise = process.argv.find(value => value.startsWith('--enterprise-id='))?.split('=')[1];
assert.match(enterprise ?? '', /^[1-9]\d{0,18}$/, '导入必须显式提供已盘点的--enterprise-id=编号');
assert.ok(BigInt(enterprise) <= 9223372036854775807n);
// 记录与浏览器临时目录放在项目内，避免沙箱限制 E:\\Codex 写入。
const artifactRoot = fileURLToPath(new URL('../.tmp/school-workspace/', import.meta.url));
await mkdir(artifactRoot, { recursive: true });
const statePath = join(artifactRoot, `school-${enterprise}.json`);
const lockPath = join(artifactRoot, `school-${enterprise}.lock`);
const lock = await open(lockPath, 'wx').catch(() => { throw Error('已有导入锁；请先核对上次进程与导入记录，禁止并发执行。'); });
let state;
// 直接用 HTTP 登录并复用会话，不再依赖 Edge 远程调试；千级导入更稳定、更快。
const accountName = process.env.ADMIN_USERNAME ?? 'adminW';
const accountPassword = process.env.ADMIN_PASSWORD;
assert.ok(accountPassword, '请先设置环境变量 ADMIN_PASSWORD（管理员口令）；脚本不会打印或记录口令。');
const baseUrl = (process.argv.find(value => value.startsWith('--base='))?.split('=')[1] ?? 'http://127.0.0.1:5173').replace(/\/$/, '');
const cookies = new Map();
const cookieHeader = () => [...cookies].map(([name, value]) => `${name}=${value}`).join('; ');
function remember(response) {
  for (const header of response.headers.getSetCookie()) {
    const pair = header.split(';')[0], index = pair.indexOf('=');
    if (index > 0) cookies.set(pair.slice(0, index), pair.slice(index + 1));
  }
}
async function send(path, init = {}) {
  const response = await fetch(baseUrl + path, { ...init, redirect: 'manual', headers: { ...(init.headers ?? {}), cookie: cookieHeader() }, signal: AbortSignal.timeout(30000) });
  remember(response);
  return response;
}
async function token() {
  const response = await send('/api/auth/csrf');
  const value = await response.json();
  assert.equal(response.status, 200, '校验令牌读取失败'); assert.equal(value.code, 0, '校验令牌读取失败');
  return value.data;
}
async function login() {
  cookies.clear();
  const value = await token();
  const response = await send('/api/auth/login', { method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded', [value.headerName]: value.token },
    body: new URLSearchParams({ username: accountName, password: accountPassword }) });
  const result = await response.json();
  assert.equal(response.status, 200, `管理员登录失败：HTTP ${response.status}`);
  assert.equal(result.code, 0, '管理员登录失败，请核对 ADMIN_USERNAME 与 ADMIN_PASSWORD');
}
async function request(path, body, file) {
  const options = { cache: 'no-store' };
  if (body !== undefined) {
    const value = await token();
    options.method = 'POST'; options.headers = { [value.headerName]: value.token };
    if (file !== undefined) {
      const form = new FormData();
      form.append('metadata', new Blob([JSON.stringify(body)], { type: 'application/json' }));
      form.append('file', new Blob([file.text], { type: 'text/markdown;charset=utf-8' }), file.name);
      options.body = form;
    } else {
      options.headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(body);
    }
  }
  let response = await send(path, options);
  if (response.status === 401) { await login(); response = await send(path, options); }
  const value = await response.json();
  return { http: response.status, code: value.code, data: value.data };
}
const ok = result => {
  assert.equal(result.http, 200, `HTTP失败：${result.http}；未自动重试`);
  assert.equal(result.code, 0, `业务失败：${result.code}；未自动重试`);
  return result.data;
};
// 每个空间只盘点一次，避免千级导入时逐篇重复拉取目录。
const rows = async path => {
  const result = [];
  for (let page = 1; page <= 20; page++) {
    const data = ok(await request(`${path}?pageNum=${page}&pageSize=100`));
    const value = data.page ?? data;
    assert.ok(Array.isArray(value.records) && value.pages <= 20, '目录超过安全盘点上限');
    result.push(...value.records);
    if (page >= value.pages) return result;
  }
  throw Error('目录读取未完成');
};
async function originalOf(path) {
  const response = await send(path);
  assert.equal(response.status, 200, '原件读取失败');
  assert.ok(response.headers.get('content-type')?.startsWith('application/octet-stream'), '原件读取失败');
  return response.text();
}
try {
  const digest = baseDigest;
  try { state = JSON.parse(await readFile(statePath, 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  state ??= { enterpriseId: enterprise, corpusSha256: digest, departments: {}, spaces: {}, documents: {} };
  assert.equal(state.enterpriseId, enterprise);
  assert.equal(state.corpusSha256, digest, '语料已变化，请人工核对版本，不覆写原导入记录。');
  const save = () => writeFile(statePath, JSON.stringify(state, null, 2) + '\n', 'utf8');
  await save();
  await login();
  const account = ok(await request('/api/auth/me'));
  assert.ok(account.enterprises?.some(item => item.enterpriseId === enterprise && item.role === 'ADMIN'),
    '当前账号不属于指定企业管理员，或后端尚未重启至新版归属接口');
  const root = `/api/enterprises/${enterprise}`;
  for (const name of corpus.departments) {
    const directory = ok(await request(root + '/organization/departments?pageNum=1&pageSize=100'));
    assert.ok(directory.page.pages <= 1, '部门超过100项，请先核对目录');
    const matches = directory.page.records.filter(item => item.name === name);
    assert.ok(matches.length <= 1, '部门名称重复，停止导入');
    if (matches.length) { assert.equal(matches[0].status, 0); state.departments[name] = matches[0].id; }
    else {
      assert.ok(!state.departments[name], '已有未核对部门写入，不自动重发');
      state.departments[name] = { pending: true }; await save();
      const created = ok(await request(root + '/organization/departments', { expectedEnterpriseVersion: directory.enterprisePermissionVersion, name }));
      state.departments[name] = created.department.id;
    }
    await save();
  }
  for (const space of corpus.spaces) {
    const matches = (await rows(root + '/knowledge-spaces')).filter(item => item.name === space.name);
    assert.ok(matches.length <= 1, '知识空间名称重复，停止导入');
    if (matches.length) state.spaces[space.name] = matches[0].id;
    else {
      assert.ok(!state.spaces[space.name], '已有未核对空间写入，不自动重发');
      state.spaces[space.name] = { pending: true }; await save();
      state.spaces[space.name] = ok(await request(root + '/knowledge-spaces', { name: space.name })).id;
      await save();
    }
    const spaceId = state.spaces[space.name];
    assert.match(spaceId, /^[1-9]\d*$/);
    // 每个空间只盘点一次，避免千级导入时逐篇重复拉取目录。
    const collection = root + `/knowledge-spaces/${spaceId}/documents`;
    const existingTitles = new Set((await rows(collection)).map(existing => existing.title));
    for (const doc of space.documents) {
      const key = `${space.name}/${doc.title}`, text = textOf(doc);
      const item = state.documents[key] ??= { uploadId: randomUUID(), jobId: randomUUID(), sha256: hash(text) };
      assert.equal(item.sha256, hash(text));
      await save();
      if (!item.versionId) {
        assert.ok(!existingTitles.has(doc.title), '存在未纳入导入记录的同名文档，请人工核对，不重复上传');
        const version = ok(await request(collection, { requestId: item.uploadId, title: doc.title }, { name: doc.title + '.md', text }));
        item.documentId = version.documentId; item.versionId = version.id; await save();
        existingTitles.add(doc.title);
      }
      assert.match(item.documentId, /^[1-9]\d*$/); assert.match(item.versionId, /^[1-9]\d*$/);
      const path = collection + '/' + item.documentId, versionPath = path + '/versions/' + item.versionId;
      let document = ok(await request(path));
      assert.equal(document.title, doc.title);
      const original = await originalOf(versionPath + '/original');
      assert.equal(original, text, '原件与学校资料不一致');
      let version = ok(await request(versionPath));
      if (version.parseStatus !== 'READY') {
        if (!item.parseRevision) { item.parseRevision = document.revision; await save(); }
        const jobsPath = versionPath + '/ingest-jobs';
        const accepted = ok(await request(jobsPath, { requestId: item.jobId, expectedRevision: item.parseRevision, action: 'PARSE' }));
        assert.equal(accepted.id, item.jobId);
        const deadline = Date.now() + 35000;
        let job;
        do {
          job = ok(await request(jobsPath + '/' + item.jobId));
          if (!['QUEUED', 'RUNNING'].includes(job.status)) break;
          await delay(250);
        } while (Date.now() < deadline);
        assert.equal(job.status, 'SUCCEEDED', '解析未成功，不发布、不换编号重试');
        version = ok(await request(versionPath));
      }
      assert.ok(version.parseStatus === 'READY' && version.chunkCount > 0);
      document = ok(await request(path));
      if (document.publishedVersionId !== item.versionId) {
        assert.equal(document.publishedVersionId, null, '已有其他发布版本，不覆盖');
        document = ok(await request(path + '/lifecycle', { expectedRevision: document.revision, versionId: item.versionId, action: 'PUBLISH' }));
      }
      assert.equal(document.publishedVersionId, item.versionId);
      item.status = 'PUBLISHED'; item.chunkCount = version.chunkCount; await save();
      console.log(`已发布：${space.name} / ${doc.title}，片段数=${version.chunkCount}`);
    }
  }
  state.completedAt = new Date().toISOString(); await save();
  console.log(`学校资料已导入并核对原件、解析和发布：${documents.length}/${documents.length}。记录：${statePath}。未清理旧数据，未构建向量，未调用模型。`);
} finally {
  await lock.close(); await unlink(lockPath);
}
