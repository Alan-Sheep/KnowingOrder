import test from 'node:test';
import assert from 'node:assert/strict';
import { createEnterpriseClient } from '../src/enterprise.ts';
import { answerView } from '../src/knowledge-answer.ts';

const ok = data => Response.json({ code: 0, data });
test('检索策略独立于模型外发模式，未知策略不伪装为关键词', async () => {
  for (const mode of ['KEYWORD', 'HYBRID']) {
    const calls = [];
    const api = createEnterpriseClient(async path => { calls.push(path); return ok(mode); });
    assert.equal(await api.retrievalMode('1', '2'), mode);
    assert.deepEqual(calls, ['/api/enterprises/1/knowledge-spaces/2/answers/retrieval-mode']);
  }
  for (const mode of [null, {}, 'VECTOR', 'UNKNOWN']) {
    const api = createEnterpriseClient(async () => ok(mode));
    await assert.rejects(api.retrievalMode('1', '2'), /策略响应/);
  }
});
test('本人改密只发送两字段并重新获取CSRF，不写缓存或自动重试', async () => {
  const calls = [];
  const api = createEnterpriseClient(async (path, init) => {
    calls.push({ path, init });
    return path.endsWith('/csrf') ? ok({ headerName: 'X-CSRF-TOKEN', token: 'synthetic-csrf' }) : ok(null);
  });
  await api.changePassword('previous-test-password', 'new-test-password');
  assert.equal(calls.length, 2);
  assert.equal(calls[1].path, '/api/auth/password');
  assert.deepEqual(JSON.parse(calls[1].init.body), { previousPassword: 'previous-test-password', newPassword: 'new-test-password' });
  assert.equal(calls[1].init.credentials, 'same-origin');
  assert.equal(calls[1].init.headers['X-CSRF-TOKEN'], 'synthetic-csrf');
});
test('非法新口令在请求前拒绝，服务失败和未知结果不重复改密', async () => {
  let calls = 0;
  const api = createEnterpriseClient(async path => {
    calls++;
    return path.endsWith('/csrf') ? ok({ headerName: 'X-CSRF-TOKEN', token: 'synthetic-csrf' })
      : new Response(null, { status: 502 });
  });
  for (const password of ['', 'short', 'old-test-password', '密'.repeat(25)])
    await assert.rejects(api.changePassword('old-test-password', password), /新口令/);
  assert.equal(calls, 0);
  await assert.rejects(api.changePassword('old-test-password', 'new-test-password'), error => error.uncertain);
  assert.equal(calls, 2);
});
test('依据复核失败可恢复为仅原文状态，不能伪装有依据的答案', () => {
  const value = { mode: 'SYNTHETIC_MODEL', status: 'EVIDENCE_ONLY', message: '复核未完成，保留原文。', elapsedMs: 100,
    evidence: [], parts: [{ id: 1, question: '餐补多少？', status: 'EVIDENCE_ONLY', paragraphs: [], gaps: ['需人工核对。'] }] };
  assert.equal(answerView(value).parts[0].paragraphs.length, 0);
  assert.throws(() => answerView({ ...value, status: 'ANSWERED', parts: [{ ...value.parts[0], status: 'ANSWERED', gaps: [] }] }), /格式/);
});
test('PDF与DOCX仅传原件，客户端不会OCR或将文件提交给外部接口', async () => {
  for (const name of ['虚构制度.pdf', '虚构制度.docx']) {
    const calls = [];
    const version = { id: '3', documentId: '4', versionNo: 1, originalName: name, fileSize: 3,
      parseStatus: 'PENDING', publicationStatus: 'DRAFT', chunkCount: 0, failureCode: null, validFromMs: null, validUntilMs: null };
    const api = createEnterpriseClient(async (path, init) => {
      calls.push({ path, init });
      return path.endsWith('/csrf') ? ok({ headerName: 'X-CSRF-TOKEN', token: 'synthetic-csrf' }) : ok(version);
    });
    const file = new File(['abc'], name);
    await api.uploadDocument('1', '2', { requestId: '90000000-0000-4000-8000-000000000001', documentId: null,
      expectedRevision: null, title: '虚构制度', validFromMs: null, validUntilMs: null }, file);
    assert.equal(calls.length, 2);
    assert.equal(calls[1].path, '/api/enterprises/1/knowledge-spaces/2/documents');
    assert.equal(await calls[1].init.body.get('file').text(), 'abc');
  }
});
