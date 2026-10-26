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
// 目标环境容量有限时用 --no-bulk 只导 18 篇基础语料；--bulk-limit=N 可只取前 N 篇（按空间轮转均分），
// 用同一份语料覆盖「18 篇基础 + N 篇演示」的中等规模环境，不必为了数量差异重新生成语料。
const withBulk = !process.argv.includes('--no-bulk');
const bulkLimit = Number(process.argv.find(value => value.startsWith('--bulk-limit='))?.split('=')[1] ?? 0);
assert.ok(Number.isInteger(bulkLimit) && bulkLimit >= 0 && bulkLimit <= 5000, '--bulk-limit 需为 0—5000 的整数');
let bulk = { spaces: [] };
if (withBulk) {
  // --bulk-file= 可指定其它演示语料（如组织运营语料 org-corpus-bulk.json），默认仍是学校学生场景语料。
  const bulkFile = process.argv.find(value => value.startsWith('--bulk-file='))?.split('=')[1] ?? 'school-corpus-bulk.json';
  try { bulk = JSON.parse(await readFile(new URL(`../.tmp/${bulkFile}`, import.meta.url), 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
}
// 轮转取篇：每个空间各取一篇再取下一篇，避免 limit 较小时前几个空间被取空、后面的空间一篇没有。
if (bulkLimit > 0 && (bulk.spaces ?? []).length) {
  const picked = new Map(bulk.spaces.map(space => [space.name, []]));
  const depth = Math.max(...bulk.spaces.map(space => space.documents.length));
  const ordered = [];
  for (let index = 0; index < depth && ordered.length < bulkLimit; index++) {
    for (const space of bulk.spaces) {
      if (index < space.documents.length && ordered.length < bulkLimit) ordered.push([space.name, space.documents[index]]);
    }
  }
  for (const [name, doc] of ordered) picked.get(name).push(doc);
  bulk = { ...(Array.isArray(bulk.departments) ? { departments: bulk.departments } : {}),
    spaces: bulk.spaces.map(space => ({ name: space.name, documents: picked.get(space.name) })) };
}
for (const space of bulk.spaces ?? []) {
  // bulk 允许引入基础语料之外的新知识空间（如组织运营语料），按名称自动注册。
  let target = corpus.spaces.find(item => item.name === space.name);
  if (!target) { target = { name: space.name, documents: [] }; corpus.spaces.push(target); }
  target.documents.push(...space.documents);
}
if (Array.isArray(bulk.departments))
  for (const name of bulk.departments) if (!corpus.departments.includes(name)) corpus.departments.push(name);
const documents = corpus.spaces.flatMap(space => space.documents);
assert.ok(new Set(documents.map(doc => doc.title)).size === documents.length, '语料标题重复');
const textOf = doc => `# ${doc.title}\n\n${corpus.edition}\n\n${doc.sections.join('\n\n')}\n\n说明：${corpus.notice}\n`;
for (const doc of documents) {
  assert.ok(doc.title.length <= 128 && doc.sections.length >= 5);
  assert.ok(Buffer.byteLength(textOf(doc), 'utf8') <= 1024 * 1024);
  assert.ok(!/office-live|PRIVATE_POLICY|org-test|联调测试/.test(textOf(doc)));
}
console.log(`学校语料校验通过：${corpus.schoolName}，${corpus.spaces.length}个知识空间，${documents.length}篇资料，${corpus.departments.length}个部门；未连接数据库。`);
if (!process.argv.includes('--import')) process.exit(0);
const enterprise = process.argv.find(value => value.startsWith('--enterprise-id='))?.split('=')[1];
assert.match(enterprise ?? '', /^[1-9]\d{0,18}$/, '导入必须显式提供已盘点的--enterprise-id=编号');
assert.ok(BigInt(enterprise) <= 9223372036854775807n);
const baseUrl = (process.argv.find(value => value.startsWith('--base='))?.split('=')[1] ?? 'http://127.0.0.1:5173').replace(/\/$/, '');
// 导入记录按目标环境隔离：同一方案在不同库上编号不同，混用会指向错的文档。
const host = new URL(baseUrl).hostname;
const targetTag = ['localhost', '127.0.0.1', '::1'].includes(host) ? 'local' : host.replace(/[^a-zA-Z0-9.-]/g, '_');
// 记录与浏览器临时目录放在项目内，避免沙箱限制 E:\\Codex 写入。
const artifactRoot = fileURLToPath(new URL('../.tmp/school-workspace/', import.meta.url));
await mkdir(artifactRoot, { recursive: true });
const statePath = join(artifactRoot, `school-${enterprise}-${targetTag}.json`);
const lockPath = join(artifactRoot, `school-${enterprise}-${targetTag}.lock`);
const lock = await open(lockPath, 'wx').catch(() => { throw Error('已有导入锁；请先核对上次进程与导入记录，禁止并发执行。'); });
let state;
// 直接用 HTTP 登录并复用会话，不再依赖 Edge 远程调试；千级导入更稳定、更快。
const accountName = process.env.ADMIN_USERNAME ?? 'adminW';
const accountPassword = process.env.ADMIN_PASSWORD;
assert.ok(accountPassword, '请先设置环境变量 ADMIN_PASSWORD（管理员口令）；脚本不会打印或记录口令。');
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
  // 高并发提交时后端解析线程池可能忙拒绝（HTTP 503 或业务 code=503）；所有写操作都带幂等 requestId，退避重试安全。
  let value; let response;
  for (let attempt = 0; ; attempt++) {
    response = await send(path, options);
    if (response.status === 401) { await login(); response = await send(path, options); }
    value = await response.json().catch(() => ({}));
    if (response.status === 503 || value.code === 503) {
      if (attempt >= 8) break;
      await delay(1500 * (attempt + 1));
      continue;
    }
    break;
  }
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
  for (let page = 1; page <= 60; page++) {
    const data = ok(await request(`${path}?pageNum=${page}&pageSize=100`));
    const value = data.page ?? data;
    assert.ok(Array.isArray(value.records) && value.pages <= 60, '目录超过安全盘点上限');
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
// 万级导入：状态写盘按秒节流（全量 JSON 很大），崩溃最多丢失最近几秒的进度记录。
let dirty = false;
const flushNow = async () => {
  if (!dirty) return;
  dirty = false;
  await writeFile(statePath, JSON.stringify(state, null, 2) + '\n', 'utf8');
};
const save = () => { dirty = true; };
const saver = setInterval(() => { flushNow().catch(() => {}); }, 2000);
const concurrency = Number(process.argv.find(value => value.startsWith('--concurrency='))?.split('=')[1] ?? 8);
assert.ok(Number.isInteger(concurrency) && concurrency >= 1 && concurrency <= 16, '--concurrency 需为 1—16 的整数');
try {
  const digest = baseDigest;
  try { state = JSON.parse(await readFile(statePath, 'utf8')); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  state ??= { enterpriseId: enterprise, corpusSha256: digest, departments: {}, spaces: {}, documents: {} };
  assert.equal(state.enterpriseId, enterprise);
  assert.equal(state.corpusSha256, digest, '语料已变化，请人工核对版本，不覆写原导入记录。');
  await login();
  const account = ok(await request('/api/auth/me'));
  assert.ok(account.enterprises?.some(item => item.enterpriseId === enterprise && item.role === 'ADMIN'),
    '当前账号不属于指定企业管理员，或后端尚未重启至新版归属接口');
  const root = `/api/enterprises/${enterprise}`;
  // 阶段一（串行）：盘点部门与知识空间，收集文档处理任务。
  const spaceJobs = [];
  for (const name of corpus.departments) {
    const directory = ok(await request(root + '/organization/departments?pageNum=1&pageSize=100'));
    assert.ok(directory.page.pages <= 1, '部门超过100项，请先核对目录');
    const matches = directory.page.records.filter(item => item.name === name);
    assert.ok(matches.length <= 1, '部门名称重复，停止导入');
    if (matches.length) { assert.equal(matches[0].status, 0); state.departments[name] = matches[0].id; }
    else {
      // 目录中不存在即可重建；state 里的残留记录（上次中断）以目录为准覆盖。
      state.departments[name] = { pending: true }; save();
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
      // 目录中不存在即可重建；state 里的残留记录（上次中断）以目录为准覆盖。
      state.spaces[space.name] = { pending: true }; save();
      state.spaces[space.name] = ok(await request(root + '/knowledge-spaces', { name: space.name })).id;
      save();
    }
    const spaceId = state.spaces[space.name];
    assert.match(spaceId, /^[1-9]\d*$/);
    // 每个空间只盘点一次，避免千级导入时逐篇重复拉取目录；标题→id 映射用于中断后认领孤儿文档。
    const collection = root + `/knowledge-spaces/${spaceId}/documents`;
    const existingDocs = new Map((await rows(collection)).map(existing => [existing.title, existing.id]));
    spaceJobs.push({ space, spaceId, collection, existingDocs });
  }
  // 阶段二：全部文档拍平为任务队列进入并发池（任务间无依赖；写操作幂等，503 由退避重试兜底）。
  let processed = 0;
  const processDocument = async (job, doc) => {
    const key = `${job.space.name}/${doc.title}`, text = textOf(doc);
    const item = state.documents[key] ??= { uploadId: randomUUID(), jobId: randomUUID(), sha256: hash(text) };
    assert.equal(item.sha256, hash(text));
    save();
    if (item.status === 'PUBLISHED' && item.chunkCount > 0) return; // 已发布且核对过片段数，跳过重复核对
    if (!item.versionId) {
      const adopted = job.existingDocs.get(doc.title);
      if (adopted) {
        // 上次中断留在库里、state 又丢失记录的孤儿文档：以库为准认领，不重复上传；
        // 已有版本但未发布的（中断在上传与发布之间）继续走解析与发布流程。
        const info = ok(await request(job.collection + '/' + adopted));
        item.documentId = String(adopted);
        item.adopted = true;
        if (info.publishedVersionId) { item.versionId = info.publishedVersionId; }
        else {
          const versions = ok(await request(job.collection + '/' + adopted + '/versions?pageNum=1&pageSize=5'));
          const candidate = (versions.page ?? versions).records?.[0];
          assert.ok(candidate?.id, '库中同名文档没有任何版本，需人工处理：' + doc.title);
          item.versionId = String(candidate.id);
        }
        save();
      } else {
        const version = ok(await request(job.collection, { requestId: item.uploadId, title: doc.title }, { name: doc.title + '.md', text }));
        item.documentId = version.documentId; item.versionId = version.id; save();
      }
    }
    assert.match(item.documentId, /^[1-9]\d*$/); assert.match(item.versionId, /^[1-9]\d*$/);
    const path = job.collection + '/' + item.documentId, versionPath = path + '/versions/' + item.versionId;
    let document = ok(await request(path));
    assert.equal(document.title, doc.title);
    if (!item.adopted) {
      try {
        const original = await originalOf(versionPath + '/original');
        assert.equal(original, text, '原件与学校资料不一致');
      } catch (error) {
        // 万级导入不因单篇核对受阻；中断续传认领的文档可能读不到 DRAFT 原件，降级为警告。
        console.log(`警告：原件核对未通过（${doc.title}）：${error.message}`);
      }
    }
    let version = ok(await request(versionPath));
    if (version.parseStatus !== 'READY') {
      if (!item.parseRevision) { item.parseRevision = document.revision; await save(); }
      const jobsPath = versionPath + '/ingest-jobs';
      const accepted = ok(await request(jobsPath, { requestId: item.jobId, expectedRevision: item.parseRevision, action: 'PARSE' }));
      assert.equal(accepted.id, item.jobId);
      const deadline = Date.now() + 120000;
      let job;
      do {
        job = ok(await request(jobsPath + '/' + item.jobId));
        if (!['QUEUED', 'RUNNING'].includes(job.status)) break;
        await delay(400);
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
    processed++;
    if (processed % 100 === 0) console.log(`进度：${processed}/${documents.length}`);
    console.log(`已发布：${job.space.name} / ${doc.title}，片段数=${version.chunkCount}`);
  };
  const queue = spaceJobs.flatMap(job => job.space.documents.map(doc => ({ job, doc })));
  let nextTask = 0;
  const worker = async () => {
    while (nextTask < queue.length) {
      const task = queue[nextTask++];
      await processDocument(task.job, task.doc);
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, queue.length) }, () => worker()));
  state.completedAt = new Date().toISOString(); await save();
  clearInterval(saver); await flushNow();
  console.log(`学校资料已导入并核对原件、解析和发布：${processed}/${documents.length}（并发=${concurrency}）。记录：${statePath}。未清理旧数据，未构建向量，未调用模型。`);
} finally {
  clearInterval(saver);
  try { await flushNow(); } catch { /* 状态已在每次关键节点尽力落盘 */ }
  await lock.close(); await unlink(lockPath);
}
