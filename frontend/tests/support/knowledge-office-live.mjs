import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

/** 仅通过已登录同源会话操作本轮新建记录；不读取管理员口令、Cookie或数据库凭据。 */
export async function checkOfficeLive({ evaluate, passed, enterprise, permissions = false, setCleanup }) {
  assert.match(enterprise, /^[1-9]\d*$/);
  const marker = `office-live-${Date.now()}-${randomUUID().slice(0, 8)}`;
  const root = `/api/enterprises/${enterprise}`;
  const documents = [];
  let spaceId, memberId, grantActive = false, permissionProof;
  // 两个随机口令只在进程内使用，不输出、不写结果文件或浏览器存储。
  const username = marker;
  const previous = `Synthetic-${randomUUID()}`, replacement = `Synthetic-${randomUUID()}`;
  const request = async (path, body, file) => evaluate(`(async()=>{
    const options={cache:'no-store',signal:AbortSignal.timeout(8000)};
    if(${body !== undefined || file !== undefined}){
      const response=await fetch('/api/auth/csrf',{cache:'no-store',signal:AbortSignal.timeout(8000)});
      const c=await response.json(); if(response.status!==200||c.code!==0)throw Error('CSRF读取失败');
      options.method='POST';options.headers={[c.data.headerName]:c.data.token};
      if(${file !== undefined}){
        const f=${JSON.stringify(file ?? null)}, form=new FormData();
        form.append('metadata',new Blob([JSON.stringify(${JSON.stringify(body ?? null)})],{type:'application/json'}));
        form.append('file',new Blob([Uint8Array.from(atob(f.base64),c=>c.charCodeAt(0))]),f.name);options.body=form;
      }else{options.headers['Content-Type']='application/json';options.body=JSON.stringify(${JSON.stringify(body ?? null)});}
    }
    const r=await fetch(${JSON.stringify(path)},options);const value=await r.json();
    return {http:r.status,code:value.code,data:value.data};
  })()`);
  const ok = response => {
    assert.equal(response.http, 200, '本机HTTP请求失败');
    assert.equal(response.code, 0, '本机业务请求失败；仅记录状态码，不输出响应正文');
    return response.data;
  };
  const cleanup = async () => {
    for (const item of documents) {
      const doc = ok(await request(item.path));
      assert.equal(doc.title, item.name, '本轮文档标记不符，禁止撤回');
      if (doc.publishedVersionId === item.versionId)
        ok(await request(item.path + '/lifecycle', { expectedRevision: doc.revision, versionId: item.versionId, action: 'WITHDRAW' }));
    }
    if (grantActive) {
      const path = root + `/knowledge-spaces/${spaceId}`;
      const space = ok(await request(path));
      ok(await request(path + '/grants', { expectedVersion: space.permissionVersion, targetType: 'MEMBER', targetId: memberId, revoke: true }));
      grantActive = false;
    }
    if (memberId) {
      ok(await request(root + `/employees/${memberId}/status`, { status: 1 }));
      let found;
      for (let page = 1; page <= 10 && !found; page++) {
        const rows = ok(await request(root + `/organization/members?pageNum=${page}&pageSize=100`)).page;
        found = rows.records.find(item => item.id === memberId);
        if (page >= rows.pages) break;
      }
      assert.equal(found?.username, username, '本轮测试员工未核对成功');
      assert.equal(found?.status, 1, '本轮测试成员未停用');
    }
  };
  setCleanup(cleanup);
  spaceId = ok(await request(root + '/knowledge-spaces', { name: marker })).id;
  assert.match(spaceId, /^[1-9]\d*$/);
  passed(`管理员创建独立标记空间，编号=${spaceId}，标记=${marker}；未修改已有知识空间`);
  const collection = root + `/knowledge-spaces/${spaceId}/documents`;
  const results = [];
  for (const kind of ['pdf', 'docx', 'forged.pdf', 'forged.docx']) {
    const valid = kind === 'pdf' || kind === 'docx';
    const text = `PRIVATE_POLICY_${randomUUID()}`;
    const bytes = valid ? officeBytes(kind, text) : Buffer.from(text);
    const name = marker + '-' + kind + (valid ? '.' + kind : '');
    const metadata = { requestId: randomUUID(), title: name };
    const file = { name, base64: bytes.toString('base64') };
    const version = ok(await request(collection, metadata, file));
    const path = collection + '/' + version.documentId;
    const base = path + '/versions/' + version.id;
    documents.push({ path, versionId: version.id, name });
    assert.equal(version.fileSize, bytes.length);
    assert.equal(ok(await request(collection, metadata, file)).id, version.id);
    const jobId = randomUUID();
    const jobBody = { requestId: jobId, expectedRevision: 1, action: 'PARSE' };
    assert.equal(ok(await request(base + '/ingest-jobs', jobBody)).id, jobId);
    assert.equal(ok(await request(base + '/ingest-jobs', jobBody)).id, jobId);
    const until = Date.now() + 35000;
    let job;
    do {
      job = ok(await request(base + '/ingest-jobs/' + jobId));
      if (!['QUEUED', 'RUNNING'].includes(job.status)) break;
      await delay(200);
    } while (Date.now() < until);
    assert.equal(job.status, valid ? 'SUCCEEDED' : 'FAILED');
    assert.equal(job.deadlineMs - job.createdMs, 30000);
    const current = ok(await request(base));
    assert.equal(current.parseStatus, valid ? 'READY' : 'FAILED');
    assert.equal(current.publicationStatus, 'DRAFT');
    assert.equal(ok(await request(path)).publishedVersionId, null);
    const original = await evaluate(`(async()=>{
      const r=await fetch(${JSON.stringify(base + '/original')},{cache:'no-store',signal:AbortSignal.timeout(8000)});
      return {status:r.status,type:r.headers.get('Content-Type'),disposition:r.headers.get('Content-Disposition'),
        cache:r.headers.get('Cache-Control'),bytes:Array.from(new Uint8Array(await r.arrayBuffer()))};
    })()`);
    assert.equal(original.status, 200);
    assert.match(original.type, /application\/octet-stream/);
    assert.match(original.disposition, /^attachment;/);
    assert.match(original.cache, /no-store/);
    assert.deepEqual(Buffer.from(original.bytes), bytes);
    if (valid) {
      const page = ok(await request(base + '/chunks'));
      assert.equal(page.total, 1);
      const chunk = page.records[0];
      assert.ok(chunk.content.includes(text));
      if (kind === 'pdf') assert.ok(chunk.content.includes('【PDF第1页】'));
      assert.equal(chunk.startOffset, 0); assert.equal(chunk.endOffset, chunk.content.length);
      const published = ok(await request(path + '/lifecycle', { expectedRevision: 2, versionId: version.id, action: 'PUBLISH' }));
      assert.equal(published.publishedVersionId, version.id);
      const withdrawn = ok(await request(path + '/lifecycle', { expectedRevision: 3, versionId: version.id, action: 'WITHDRAW' }));
      assert.equal(withdrawn.publishedVersionId, null);
      assert.equal(ok(await request(base)).publicationStatus, 'WITHDRAWN');
    } else {
      assert.equal(job.failureCode, 'INVALID_TEXT'); assert.ok(job.message);
      assert.equal(current.chunkCount, 0);
      assert.equal((await request(path + '/lifecycle', { expectedRevision: 2, versionId: version.id, action: 'PUBLISH' })).code, 409);
    }
    results.push({ kind, documentId: version.documentId, versionId: version.id, jobId, status: job.status,
      publicationStatus: valid ? 'WITHDRAWN' : 'DRAFT', originalBytesEqual: true });
    passed(`${kind}真实入库终态、原件字节及发布边界核对通过`);
  }
  const employee = ok(await request(root + '/employees', { username, password: previous }));
  memberId = employee.memberId;
  assert.match(memberId, /^[1-9]\d*$/);
  passed(`创建标记测试员工，成员编号=${memberId}，账号=${username}；未授予知识空间权限`);
  const first = isolatedClient(), second = isolatedClient(), fresh = isolatedClient();
  await first.login(username, previous); await second.login(username, previous);
  assert.equal((await first.get('/api/auth/me')).status, 200);
  assert.equal((await second.get('/api/auth/me')).status, 200);
  if (permissions) {
    const spacePath = root + `/knowledge-spaces/${spaceId}`;
    const target = results.find(item => item.kind === 'pdf');
    const docPath = collection + '/' + target.documentId;
    const versionPath = docPath + '/versions/' + target.versionId;
    const assertJson = async (response, code) => {
      assert.equal(response.status, 200, '权限接口HTTP状态不符');
      const json = await response.json(); assert.equal(json.code, code, '权限业务码不符');
      return json.data;
    };
    const denied = response => assertJson(response, 404);
    const employeeFile = Buffer.from(`PRIVATE_PERMISSION_${randomUUID()}`);
    const employeeName = marker + '-maintainer.txt';
    const upload = () => first.upload(collection, { requestId: randomUUID(), title: employeeName }, employeeName, employeeFile);
    await denied(await first.get(spacePath));
    await denied(await first.get(versionPath + '/original'));
    await denied(await upload());
    passed('未授权员工不能读取空间或原件，也不能上传；服务端返回404');
    const grant = async permission => {
      const space = ok(await request(spacePath));
      const body = { expectedVersion: space.permissionVersion, targetType: 'MEMBER', targetId: memberId,
        ...(permission ? { permission } : { revoke: true }) };
      const changed = ok(await request(spacePath + '/grants', body));
      grantActive = Boolean(permission);
      return changed;
    };
    const reader = await grant('READ');
    await assertJson(await first.get(spacePath), 0);
    await denied(await first.get(versionPath));
    await denied(await first.get(versionPath + '/original'));
    await denied(await first.get(spacePath + '/grants'));
    await denied(await upload());
    await denied(await first.post(docPath + '/lifecycle', { expectedRevision: 4, versionId: target.versionId, action: 'PUBLISH' }));
    await denied(await first.post(spacePath + '/grants', { expectedVersion: reader.permissionVersion, targetType: 'MEMBER', targetId: memberId, permission: 'MAINTAIN' }));
    const before = ok(await request(docPath));
    ok(await request(docPath + '/lifecycle', { expectedRevision: before.revision, versionId: target.versionId, action: 'PUBLISH' }));
    await assertJson(await first.get(versionPath), 0);
    const original = await first.get(versionPath + '/original');
    assert.equal(original.status, 200);
    assert.match(original.headers.get('Cache-Control'), /no-store/);
    assert.ok((await original.arrayBuffer()).byteLength > 0);
    assert.equal((await assertJson(await first.get(versionPath + '/chunks'), 0)).total, 1);
    passed('只读员工只可读取已发布版本与原件，不能上传、发布或给自己提权');
    await grant('MAINTAIN');
    await assertJson(await first.get(spacePath + '/grants'), 0);
    const own = await assertJson(await upload(), 0);
    const ownPath = collection + '/' + own.documentId;
    documents.push({ path: ownPath, versionId: own.id, name: employeeName });
    const jobPath = ownPath + '/versions/' + own.id + '/ingest-jobs';
    const jobId = randomUUID();
    await assertJson(await first.post(jobPath, { requestId: jobId, expectedRevision: 1, action: 'PARSE' }), 0);
    let job; const until = Date.now() + 35000;
    do {
      job = await assertJson(await first.get(jobPath + '/' + jobId), 0);
      if (!['QUEUED', 'RUNNING'].includes(job.status)) break;
      await delay(200);
    } while (Date.now() < until);
    assert.equal(job.status, 'SUCCEEDED');
    await assertJson(await first.post(ownPath + '/lifecycle', { expectedRevision: 2, versionId: own.id, action: 'PUBLISH' }), 0);
    await assertJson(await first.post(ownPath + '/lifecycle', { expectedRevision: 3, versionId: own.id, action: 'WITHDRAW' }), 0);
    passed('维护员工可上传、受理解析、发布及撤回自己的虚构文档');
    await grant(undefined);
    for (const client of [first, second]) {
      await denied(await client.get(spacePath));
      await denied(await client.get(versionPath + '/original'));
      await denied(await client.get(versionPath + '/chunks'));
    }
    await denied(await upload());
    await denied(await first.post(docPath + '/lifecycle', { expectedRevision: 5, versionId: target.versionId, action: 'WITHDRAW' }));
    await denied(await first.get(spacePath.replace(`/enterprises/${enterprise}/`, '/enterprises/9223372036854775807/')));
    passed('撤权后两条原会话均不能再读原件/片段或写入，篡改企业编号同样被拒绝');
    permissionProof = { transitions: ['NONE', 'READ', 'MAINTAIN', 'REVOKED'], oldSessionsChecked: 2,
      ownDocumentId: own.documentId, ownVersionId: own.id, enterprisePathTamperingDenied: true,
      boundary: '同企业单个合成员工的角色切换；不是两个真实企业、部门授权或并发撤权验收' };
  }
  assert.equal((await first.post('/api/auth/password', { previousPassword: previous, newPassword: replacement }, false)).status, 403);
  const changed = await first.post('/api/auth/password', { previousPassword: previous, newPassword: replacement });
  assert.equal(changed.status, 200); assert.equal((await changed.json()).code, 0);
  assert.equal((await first.get('/api/auth/me')).status, 401);
  assert.equal((await second.get('/api/auth/me')).status, 401);
  await fresh.login(username, replacement);
  assert.equal((await fresh.get('/api/auth/me')).status, 200);
  await fresh.post('/api/auth/logout', undefined);
  passed('新建测试员工本人改密成功，两条旧会话401，新口令重新登录成功；未修改管理员口令');
  await cleanup();
  ok(await request('/api/auth/me'));
  passed('本轮成功版本已撤回、测试成员已停用，管理员原会话仍有效');
  setCleanup(undefined);
  return { checkedAt: new Date().toISOString(), enterpriseId: enterprise, spaceId, marker, memberId, username,
    memberStatus: 1, files: results, permissionProof, oldSessionsRejected: 2, providerCalls: 0,
    boundary: '既有dev后端真实HTTP与数据库；未执行DDL、未读取数据库凭据、未验证并发改密竞态或模型语义' };
}

/** 单独的进程内Cookie容器只持有脚本生成测试员工的会话，不读取浏览器Cookie。 */
function isolatedClient() {
  const cookies = new Map();
  const send = async (path, init = {}) => {
    const response = await fetch('http://127.0.0.1:8080' + path, { ...init, redirect: 'manual', signal: AbortSignal.timeout(8000),
      headers: { ...init.headers, Cookie: [...cookies].map(([name, value]) => name + '=' + value).join('; ') } });
    for (const header of response.headers.getSetCookie()) {
      const pair = header.split(';', 1)[0], index = pair.indexOf('=');
      const name = pair.slice(0, index), value = pair.slice(index + 1);
      if (value) cookies.set(name, value); else cookies.delete(name);
    }
    return response;
  };
  const token = async () => {
    const r = await send('/api/auth/csrf'); assert.equal(r.status, 200);
    const c = await r.json(); assert.equal(c.code, 0); return c.data;
  };
  return {
    get: path => send(path),
    async upload(path, metadata, name, bytes) {
      const c = await token(), form = new FormData();
      form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
      form.append('file', new Blob([bytes]), name);
      return send(path, { method: 'POST', headers: { [c.headerName]: c.token }, body: form });
    },
    async post(path, body, csrf = true) {
      const c = csrf ? await token() : undefined;
      return send(path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(c ? { [c.headerName]: c.token } : {}) },
        body: body === undefined ? '{}' : JSON.stringify(body) });
    },
    async login(username, password) {
      const c = await token();
      const r = await send('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', [c.headerName]: c.token },
        body: new URLSearchParams({ username, password }).toString() });
      assert.equal(r.status, 200, '测试员工登录失败；未输出响应或口令'); assert.equal((await r.json()).code, 0);
    }
  };
}

/** 极小合成文件：PDF具备正确xref偏移，DOCX使用带CRC32的未压缩ZIP，不引入生成依赖。 */
function officeBytes(kind, text) {
  if (kind === 'pdf') {
    const stream = `BT /F1 12 Tf 40 700 Td (${text}) Tj ET`;
    const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
      '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
      `<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];
    let pdf = '%PDF-1.4\n'; const offsets = [0];
    objects.forEach((value, index) => { offsets.push(Buffer.byteLength(pdf)); pdf += `${index + 1} 0 obj\n${value}\nendobj\n`; });
    const start = Buffer.byteLength(pdf);
    pdf += 'xref\n0 6\n0000000000 65535 f \n' + offsets.slice(1).map(n => String(n).padStart(10, '0') + ' 00000 n \n').join('');
    return Buffer.from(pdf + `trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${start}\n%%EOF\n`);
  }
  const entries = [['[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>'],
    ['_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>'],
    ['word/document.xml', `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>${text}</w:t></w:r></w:p></w:body></w:document>`]];
  const local = [], directory = []; let offset = 0;
  for (const [path, text] of entries) {
    const name = Buffer.from(path), data = Buffer.from(text); let crc = 0xffffffff;
    for (const value of data) { crc ^= value; for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); }
    crc = (crc ^ 0xffffffff) >>> 0;
    const header = Buffer.alloc(30); header.writeUInt32LE(0x04034b50); header.writeUInt16LE(20, 4);
    header.writeUInt32LE(crc, 14); header.writeUInt32LE(data.length, 18); header.writeUInt32LE(data.length, 22); header.writeUInt16LE(name.length, 26);
    const central = Buffer.alloc(46); central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(name.length, 28); central.writeUInt32LE(offset, 42);
    local.push(header, name, data); directory.push(central, name); offset += header.length + name.length + data.length;
  }
  const listing = Buffer.concat(directory), end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(listing.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, listing, end]);
}
