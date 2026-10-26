import test from 'node:test';
import assert from 'node:assert/strict';
import { createEnterpriseClient } from '../src/enterprise.ts';
import { workspaceJob, workspaceChats, workspaceHistory, feedbackList } from '../src/knowledge-workspace.ts';

const chatId = '11111111-1111-4111-8111-111111111111';
const requestId = '22222222-2222-4222-8222-222222222222';
const feedbackId = '33333333-3333-4333-8333-333333333333';
const fixture = { id: requestId, chatId, question: '餐补多少？', status: 'PENDING', createdMs: 1000,
  deadlineMs: 31000, finishedMs: null, answer: null, message: '已受理' };
const ok = data => Response.json({ code: 0, data });
const csrf = { headerName: 'X-CSRF-TOKEN', token: 'synthetic-token' };

test('学校范围反馈可随原任务恢复，任务完成不冒充问题已经回答', () => {
  for (const status of ['OUT_OF_SCOPE', 'SCOPE_UNKNOWN', 'SCOPE_UNAVAILABLE']) {
    const answer = { mode: 'SYNTHETIC_MODEL', status, message: '学校范围处理结果。', elapsedMs: 20, evidence: [],
      parts: [{ id: 1, question: fixture.question, status, paragraphs: [], gaps: ['未生成制度结论。'] }] };
    const result = workspaceHistory([{ ...fixture, status: 'COMPLETED', finishedMs: 2000, answer }]);
    assert.equal(result[0].status, 'COMPLETED'); assert.equal(result[0].answer.status, status);
  }
});

test('任务UUID、首次硬截止和终态契约严格校验', () => {
  assert.deepEqual(workspaceJob(fixture), fixture);
  for (const value of [null, {}, { ...fixture, id: 1 }, { ...fixture, deadlineMs: 31001 },
    { ...fixture, status: 'RETRY_FOREVER' }, { ...fixture, finishedMs: 'secret' },
    { ...fixture, answer: {} }]) assert.throws(() => workspaceJob(value), /格式/);
  assert.throws(() => workspaceHistory([fixture, fixture]), /格式/);
  assert.throws(() => workspaceChats([{ id: chatId, title: '', createdMs: 1 }]), /格式/);
});
test('提交携带原会话和幂等请求编号，不带客户端身份，重复人工提交保留编号', async () => {
  const requests = [];
  const api = createEnterpriseClient(async (path, init) => {
    requests.push({ path, init }); return path.endsWith('/csrf') ? ok(csrf) : ok(fixture);
  });
  for (let i = 0; i < 2; i++) await api.submitJob('1', '401', chatId, requestId, fixture.question);
  const writes = requests.filter(x => x.init.method === 'POST');
  assert.equal(writes.length, 2);
  for (const write of writes) {
    assert.deepEqual(JSON.parse(write.init.body), { chatId, requestId, question: fixture.question });
    assert.equal(write.init.credentials, 'same-origin'); assert.equal(write.init.headers['X-CSRF-TOKEN'], csrf.token);
  }
});
test('刷新后只查询会话与原任务，不提交新问题', async () => {
  const calls = [];
  const api = createEnterpriseClient(async (path, init) => {
    calls.push({ path, init });
    return ok(path.includes('/jobs/') ? fixture : path.endsWith(chatId) ? [fixture] : [{ id: chatId, title: '餐补', createdMs: 1000 }]);
  });
  await api.chats('1', '401'); await api.chatHistory('1', '401', chatId); await api.readJob('1', '401', requestId);
  assert.equal(calls.length, 3); assert.ok(calls.every(call => !call.init.method || call.init.method === 'GET'));
});
test('任务提交断网不自动重试，并明确结果未知', async () => {
  let writes = 0;
  const api = createEnterpriseClient(async (path) => {
    if (path.endsWith('/csrf')) return ok(csrf); writes++; throw new Error('SECRET_TOKEN');
  });
  await assert.rejects(api.submitJob('1', '401', chatId, requestId, '问题'), error =>
    error.uncertain && /未知/.test(error.message) && !/SECRET/.test(error.message));
  assert.equal(writes, 1);
});
test('取消使用CSRF并返回原任务终态，读失败不新建任务', async () => {
  const paths = [];
  const api = createEnterpriseClient(async path => {
    paths.push(path); return path.endsWith('/csrf') ? ok(csrf) : ok({ ...fixture, status: 'CANCELLED', finishedMs: '2000' });
  });
  assert.equal((await api.cancelJob('1', '401', requestId)).finishedMs, 2000);
  assert.equal(paths.length, 2); assert.ok(paths[1].endsWith(`/jobs/${requestId}/cancel`));
});
test('非法UUID在获取CSRF前拒绝，不能拼接其他路径', async () => {
  let calls = 0;
  const api = createEnterpriseClient(async () => { calls++; return ok(fixture); });
  await assert.rejects(api.submitJob('1', '401', '../other', requestId, '问题'), /UUID/);
  await assert.rejects(api.readJob('1', '401', 'not-a-uuid'), /UUID/);
  assert.equal(calls, 0);
});
test('反馈契约与乐观处理版本校验，不接受伪造终态', () => {
  const item = { id: feedbackId, jobId: requestId, chunkId: null, category: 'MISSING', commentText: '缺少资料',
    status: 'OPEN', resolution: null, resolvedBy: null, revision: 1, createdMs: 1, question: '餐补多少？' };
  assert.deepEqual(feedbackList([item]), [item]);
  for (const value of [{ ...item, status: 'AUTO_FIXED' }, { ...item, revision: 0 }, { ...item, category: 'HACK' }])
    assert.throws(() => feedbackList([value]), /格式/);
  for (const value of [{ ...item, chunkId: 1 }, { ...item, chunkId: '9223372036854775808' },
    { ...item, resolvedBy: '<script>' }, { ...item, status: 'RESOLVED' }]) assert.throws(() => feedbackList([value]), /格式/);
});

test('终态必须有合法完成时间，拒绝布尔值、对象和时间顺序损坏', () => {
  for (const value of [true, false, {}, [], '', '1e3', ' 2000 ', '0002000'])
    assert.throws(() => workspaceJob({ ...fixture, status: 'COMPLETED', finishedMs: value }), /格式/);
  for (const value of [{ ...fixture, status: 'COMPLETED' }, { ...fixture, finishedMs: 2000 },
    { ...fixture, status: 'CANCELLED', finishedMs: 999 }]) assert.throws(() => workspaceJob(value), /格式/);
  assert.equal(workspaceJob({ ...fixture, status: 'COMPLETED', finishedMs: '2000' }).finishedMs, 2000);
});

test('追问关联与无法确定的提示随原任务恢复，不把摘录当已生成答案', async () => {
  for (const message of ['已按同会话历史问题关联话题，请结合引用核对。', '未能可靠确定历史话题，本次仅处理当前原问题，未自动重试。']) {
    const result = { ...fixture, question: '那找谁审批？', status: 'COMPLETED', finishedMs: 2000,
      answer: { mode: 'SYNTHETIC_MODEL', status: 'NO_EVIDENCE', message, elapsedMs: 100, evidence: [],
        parts: [{ id: 1, question: '那找谁审批？', status: 'NO_EVIDENCE', paragraphs: [], gaps: ['缺少明确对象的依据。'] }] } };
    let reads = 0;
    const api = createEnterpriseClient(async (_path, init) => {
      assert.ok(!init.method || init.method === 'GET'); reads++; return ok(result);
    });
    const restored = await api.readJob('1', '401', requestId);
    assert.equal(restored.question, '那找谁审批？'); assert.equal(restored.answer.message, message);
    assert.equal(restored.answer.parts[0].paragraphs.length, 0); assert.equal(reads, 1);
  }
});
