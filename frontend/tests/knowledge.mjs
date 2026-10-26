import test from 'node:test';
import assert from 'node:assert/strict';
import { createEnterpriseClient } from '../src/enterprise.ts';
import { documentView, versionView, chunkView, grantView, pageView } from '../src/knowledge.ts';

const ok = data => Response.json({ code: 0, data });
const document = { id: '11', knowledgeSpaceId: '2', title: '企业制度', ownerMemberId: '3', publishedVersionId: null, revision: 1 };
const version = { id: '12', documentId: '11', versionNo: 1, originalName: '制度.md', fileSize: 6,
  parseStatus: 'PENDING', publicationStatus: 'DRAFT', chunkCount: 0, failureCode: null, validFromMs: null, validUntilMs: null };
const chunk = { id: '13', chunkIndex: 0, content: '正文', startOffset: 0, endOffset: 2, startLine: 1, endLine: 1 };
const metadata = { requestId: '00000000-0000-4000-8000-000000000001', documentId: null, expectedRevision: null,
  title: '企业制度', validFromMs: null, validUntilMs: null };
const file = new File(['正文'], '制度.md');
const envelope = records => ({ records, total: records.length, pageNum: 1, pageSize: 20, pages: 1 });

test('知识契约拒绝空值、数值Long、未知状态、错误位置与分页', () => {
  assert.deepEqual(documentView(document), document);
  assert.deepEqual(versionView(version), version);
  assert.deepEqual(chunkView(chunk), chunk);
  for (const value of [null, { ...document, id: 11 }, { ...document, revision: 0 }, { ...document, ownerMemberId: null }])
    assert.throws(() => documentView(value), /格式/);
  for (const value of [{ ...version, parseStatus: 'SUCCESS' }, { ...version, fileSize: 1048577 },
    { ...version, failureCode: 'SQL_SECRET' }, { ...version, validUntilMs: '-1' }]) assert.throws(() => versionView(value), /格式/);
  for (const value of [{ ...chunk, endOffset: 3 }, { ...chunk, endLine: 0 }, { ...chunk, content: '' }])
    assert.throws(() => chunkView(value), /格式/);
  assert.throws(() => grantView({ id: '1', memberId: '2', departmentId: '3', permission: 'READ', status: 0 }), /格式/);
  assert.throws(() => pageView({ ...envelope([document]), pageSize: 0 }, documentView), /格式/);
});

test('上传使用multipart与新CSRF，人工重试保留同UUID，不自动重发', async () => {
  const sent = [];
  const api = createEnterpriseClient(async (path, init) => {
    if (path.endsWith('/csrf')) return ok({ headerName: 'X-CSRF-TOKEN', token: 'test-only' });
    assert.equal(init.headers['X-CSRF-TOKEN'], 'test-only');
    assert.equal(init.headers['Content-Type'], undefined);
    assert.equal(init.credentials, 'same-origin');
    assert.ok(init.body instanceof FormData);
    sent.push(JSON.parse(await init.body.get('metadata').text()));
    assert.equal(await init.body.get('file').text(), '正文');
    return sent.length === 1 ? new Response(null, { status: 502 }) : ok(version);
  });
  await assert.rejects(api.uploadDocument('1', '2', metadata, file), error => error.uncertain && /结果未知/.test(error.message));
  assert.equal(sent.length, 1);
  assert.deepEqual(await api.uploadDocument('1', '2', metadata, file), version);
  assert.deepEqual(sent, [metadata, metadata]);
});

test('非法文件和分页先于网络拒绝', async () => {
  let calls = 0;
  const api = createEnterpriseClient(async () => { calls++; return ok(null); });
  for (const value of [new File([], '空.txt'), new File(['x'], 'a.exe'), new File([new Uint8Array(1048577)], '大.md')])
    await assert.rejects(api.uploadDocument('1', '2', metadata, value), /TXT/);
  for (const value of [0, -1, 1.5, NaN, Number.MAX_SAFE_INTEGER + 1]) {
    await assert.rejects(api.documents('1', '2', value), /页码/);
    await assert.rejects(api.versions('1', '2', '11', value), /页码/);
    await assert.rejects(api.chunks('1', '2', '11', '12', value), /页码/);
  }
  assert.equal(calls, 0);
});

test('原件仅接受有界二进制；过期、撤权和内部错误给安全反馈', async () => {
  const api = createEnterpriseClient(async () => new Response('正文', { headers: { 'Content-Type': 'application/octet-stream;charset=UTF-8' } }));
  assert.equal(await (await api.original('1', '2', '11', '12')).text(), '正文');
  for (const response of [ok({ storageKey: 'SECRET' }), new Response(null, { headers: { 'Content-Type': 'application/octet-stream' } }),
    new Response(new Uint8Array(1048577), { headers: { 'Content-Type': 'application/octet-stream' } })])
    await assert.rejects(createEnterpriseClient(async () => response).original('1', '2', '11', '12'), /原件响应/);
  for (const code of [400, 401, 404, 409, 503, 504]) {
    await assert.rejects(createEnterpriseClient(async () => Response.json({ code, message: 'SECRET_SQL_PASSWORD' }))
      .document('1', '2', '11'), error => error.code === code && !error.message.includes('SECRET'));
  }
});

test('解析、发布和负责人更新均带CSRF与预期修订号', async () => {
  const writes = [];
  const api = createEnterpriseClient(async (path, init) => {
    if (path.endsWith('/csrf')) return ok({ headerName: 'X-CSRF-TOKEN', token: 'test-only' });
    writes.push({ path, body: JSON.parse(init.body) });
    assert.equal(init.headers['X-CSRF-TOKEN'], 'test-only');
    return ok(path.endsWith('/parse') ? { ...version, parseStatus: 'READY', chunkCount: 1 } : document);
  });
  await api.parseVersion('1', '2', '11', '12', 2);
  await api.lifecycle('1', '2', '11', '12', 3, 'PUBLISH');
  await api.assignOwner('1', '2', '11', 4, '5');
  assert.deepEqual(writes.map(value => value.body), [
    { expectedRevision: 2 }, { versionId: '12', expectedRevision: 3, action: 'PUBLISH' }, { expectedRevision: 4, ownerMemberId: '5' }
  ]);
});

test('上传超时不循环重试，发出写请求后保留未知结果', async () => {
  let writes = 0;
  const api = createEnterpriseClient(async (path, init) => {
    if (path.endsWith('/csrf')) return ok({ headerName: 'X-CSRF-TOKEN', token: 'test-only' });
    writes++;
    return new Promise((_resolve, reject) => init.signal.addEventListener('abort', () => reject(new DOMException('取消', 'AbortError')), { once: true }));
  }, 20);
  await assert.rejects(api.uploadDocument('1', '2', metadata, file), error => error.uncertain && /原请求编号/.test(error.message));
  assert.equal(writes, 1);
});
