import test from 'node:test';
import assert from 'node:assert/strict';
import { createEnterpriseClient } from '../src/enterprise.ts';
import { answerView, crossAnswerView, evidenceView } from '../src/knowledge-answer.ts';

const content = '虚构餐补每日30元，需主管审批。';
const evidence = { chunkId: '9007199254740993', documentId: '2', versionId: '3', versionNo: 1,
  title: '虚构制度', chunkIndex: 0, content, startOffset: 0, endOffset: content.length, startLine: 1, endLine: 1 };
const answer = { mode: 'SYNTHETIC_MODEL', status: 'ANSWERED', message: '仅用于虚构资料验收。', elapsedMs: 100,
  evidence: [evidence], parts: [{ id: 1, question: '餐补多少？', status: 'ANSWERED', gaps: [], paragraphs: [
    { text: '每日30元。', citations: [{ sourceId: 1, evidence, quote: '虚构餐补每日30元' }] }
  ] }] };
const ok = data => Response.json({ code: 0, data });

function crossAnswer(count = 9) {
  const schoolContent = '忘记校园网密码时，通过官方入口核验预留联系方式后重置。';
  const sources = Array.from({ length: count }, (_, index) => ({ spaceId: String(401 + Math.floor(index / 2)),
    spaceName: '校园网络与信息服务', evidence: { ...evidence, chunkId: String(index + 1), title: '校园网密码重置指南',
      content: schoolContent, endOffset: schoolContent.length } }));
  return { ...structuredClone(answer), evidence: sources, parts: [{ id: 1, question: '校园网密码怎么重置？',
    status: 'ANSWERED', gaps: [], paragraphs: [{ text: '通过官方入口核验预留联系方式后重置密码。',
      citations: [{ sourceId: count, evidence: sources[count - 1].evidence, quote: schoolContent }] }] }] };
}

test('跨空间回答接受7至9条依据，第9条引用保留来源空间与原文', () => {
  for (const count of [6, 7, 8, 9]) {
    const value = crossAnswer(count);
    const result = crossAnswerView(value);
    assert.equal(result.evidence.length, count);
    assert.equal(result.parts[0].paragraphs[0].citations[0].sourceId, count);
    assert.deepEqual(result.parts[0].paragraphs[0].citations[0].evidence, value.evidence[count - 1].evidence);
  }
});

test('跨空间9条依据支持本地摘录及模型失败，不能伪装成生成成功', () => {
  for (const [mode, status] of [['LOCAL_EVIDENCE', 'EVIDENCE_ONLY'], ['SYNTHETIC_MODEL', 'MODEL_UNAVAILABLE']]) {
    const value = crossAnswer();
    value.mode = mode; value.status = status;
    value.parts = [{ id: 1, question: '校园网密码怎么重置？', status, paragraphs: [], gaps: ['请核对原文。'] }];
    assert.equal(crossAnswerView(value).status, status);
  }
});

test('跨空间超出9条、伪造第9条引文、来源不一致及重复片段仍拒绝；单空间保持6条上限', () => {
  assert.throws(() => crossAnswerView(crossAnswer(10)), /格式/);
  const value = crossAnswer();
  const forged = structuredClone(value); forged.parts[0].paragraphs[0].citations[0].quote = '不在原文中的规定';
  assert.throws(() => crossAnswerView(forged), /格式/);
  const changed = structuredClone(value);
  changed.parts[0].paragraphs[0].citations[0].evidence = { ...changed.evidence[8].evidence, title: '另一份制度' };
  assert.throws(() => crossAnswerView(changed), /格式/);
  const duplicated = structuredClone(value); duplicated.evidence[8] = duplicated.evidence[0];
  assert.throws(() => crossAnswerView(duplicated), /格式/);
  assert.throws(() => answerView({ ...value, evidence: value.evidence.map(item => item.evidence) }), /格式/);
});

test('企业跨空间客户端展示9条合法依据，单次请求不因解析重复调用模型', async () => {
  const value = crossAnswer(); const calls = [];
  const api = createEnterpriseClient(async (path, init) => {
    calls.push({ path, init });
    return path.endsWith('/csrf') ? ok({ headerName: 'X-CSRF-TOKEN', token: 'synthetic-token' }) : ok(value);
  });
  assert.deepEqual(await api.crossAnswer('1', '校园网密码怎么重置？'), crossAnswerView(value));
  assert.equal(calls.length, 2);
  assert.equal(calls[1].path, '/api/enterprises/1/answers');
  assert.deepEqual(JSON.parse(calls[1].init.body), { question: '校园网密码怎么重置？' });
});

test('回答保留Long字符串和原文坐标，引用指向服务端返回的同一份依据', () => {
  const result = answerView(answer);
  assert.equal(result.evidence[0].chunkId, '9007199254740993');
  assert.deepEqual(result.parts[0].paragraphs[0].citations[0].evidence, evidence);
});

test('非法坐标、数值ID、过大内容和原件信息不进入引用视图', () => {
  for (const value of [null, {}, { ...evidence, chunkId: 1 }, { ...evidence, endOffset: -1 },
    { ...evidence, endLine: 0 }, { ...evidence, content: 'x'.repeat(1001) }]) assert.throws(() => evidenceView(value), /格式/);
  assert.equal('storageKey' in evidenceView({ ...evidence, storageKey: 'not-for-ui' }), false);
});

test('未知状态、伪造引用、重复来源、空段及无依据的假成功均被拒绝', () => {
  const mutations = [
    value => { value.status = 'SUCCESS'; }, value => { value.parts[0].paragraphs[0].citations[0].sourceId = 2; },
    value => { value.parts[0].paragraphs[0].citations[0].quote = '每天3000元'; },
    value => { value.parts[0].paragraphs[0].citations[0].evidence = { ...evidence, title: '替换资料' }; },
    value => { value.evidence.push(value.evidence[0]); }, value => { value.parts = []; },
    value => { value.parts[0].paragraphs = []; }, value => { value.mode = 'LOCAL_EVIDENCE'; }
  ];
  for (const mutate of mutations) { const value = structuredClone(answer); mutate(value); assert.throws(() => answerView(value), /格式/); }
});

test('本地摘录必须明确未作答，资料不足可以返回空依据', () => {
  for (const [status, sources] of [['EVIDENCE_ONLY', [evidence]], ['NO_EVIDENCE', []]]) {
    const result = answerView({ ...answer, mode: 'LOCAL_EVIDENCE', status, evidence: sources,
      parts: [{ id: 1, question: '餐补？', status, paragraphs: [], gaps: ['需核对原文或补充资料。'] }] });
    assert.equal(result.parts[0].paragraphs.length, 0);
  }
});

test('私有回答沿用Cookie和新CSRF，不由客户端声明外发许可或员工身份', async () => {
  const requests = [];
  const api = createEnterpriseClient(async (path, init) => {
    requests.push({ path, init });
    return path.endsWith('/csrf') ? ok({ headerName: 'X-CSRF-TOKEN', token: 'synthetic-token' }) : ok(answer);
  });
  assert.deepEqual(await api.answer('1', '401', '餐补多少？'), answerView(answer));
  assert.equal(requests.length, 2);
  assert.equal(requests[1].path, '/api/enterprises/1/knowledge-spaces/401/answers');
  assert.deepEqual(JSON.parse(requests[1].init.body), { question: '餐补多少？' });
  assert.equal(requests[1].init.credentials, 'same-origin');
  assert.equal(requests[1].init.headers['X-CSRF-TOKEN'], 'synthetic-token');
});

test('空值和越界问题不发请求；超时业务失败不自动重试或透出原始错误', async () => {
  let calls = 0;
  const api = createEnterpriseClient(async path => {
    calls++;
    return path.endsWith('/csrf') ? ok({ headerName: 'X-CSRF-TOKEN', token: 'test' })
      : Response.json({ code: 504, message: 'SECRET_UPSTREAM_TOKEN' });
  });
  for (const question of ['', ' ', '字'.repeat(1201)]) await assert.rejects(api.answer('1', '401', question), /问题/);
  assert.equal(calls, 0);
  await assert.rejects(api.answer('1', '401', '餐补？'), error => /超过时限/.test(error.message) && !error.message.includes('SECRET'));
  assert.equal(calls, 2);
});

test('每次打开引用重新调用私有API；失效资料不使用旧返回值替代', async () => {
  let calls = 0;
  const api = createEnterpriseClient(async path => {
    assert.equal(path, '/api/enterprises/1/knowledge-spaces/401/answers/evidence/9007199254740993');
    return ++calls === 1 ? ok(evidence) : Response.json({ code: 404, message: 'SECRET' });
  });
  assert.deepEqual(await api.evidence('1', '401', evidence.chunkId), evidence);
  await assert.rejects(api.evidence('1', '401', evidence.chunkId), /不存在或不可访问/);
  assert.equal(calls, 2);
});

test('提问前读取服务端外发边界，未知模式拒绝使用', async () => {
  for (const mode of ['LOCAL_EVIDENCE', 'SYNTHETIC_MODEL']) {
    const api = createEnterpriseClient(async path => {
      assert.equal(path, '/api/enterprises/1/knowledge-spaces/401/answers/mode');
      return ok(mode);
    });
    assert.equal(await api.answerMode('1', '401'), mode);
  }
  await assert.rejects(createEnterpriseClient(async () => ok('PUBLIC_WEB')).answerMode('1', '401'), /模式/);
});

test('学校范围外、未知与识别故障分别展示，不冒充资料不足或已作答', () => {
  for (const status of ['OUT_OF_SCOPE', 'SCOPE_UNKNOWN', 'SCOPE_UNAVAILABLE']) {
    const value = { ...answer, status, evidence: [], parts: [
      { id: 1, question: '宠物保险怎么买？', status, paragraphs: [], gaps: ['请核对业务范围；未自动外发。'] }
    ] };
    assert.equal(answerView(value).status, status);
    const forged = structuredClone(value); forged.parts[0].paragraphs = answer.parts[0].paragraphs;
    forged.evidence = answer.evidence;
    assert.throws(() => answerView(forged), /格式/);
  }
});

test('学校混合三问保留顺序，校内引用与校外反馈同时可读', () => {
  const value = structuredClone(answer); value.status = 'PARTIAL';
  value.parts.push({ id: 2, question: '宠物保险呢？', status: 'OUT_OF_SCOPE', paragraphs: [], gaps: ['超出学校业务范围。'] },
    { id: 3, question: '多久到账？', status: 'NO_EVIDENCE', paragraphs: [], gaps: ['缺少到账日期。'] });
  assert.deepEqual(answerView(value).parts.map(part => part.status), ['ANSWERED', 'OUT_OF_SCOPE', 'NO_EVIDENCE']);
  assert.deepEqual(answerView(value).parts[0].paragraphs[0].citations[0].evidence, evidence);
});

test('本地空间可说明范围识别无许可，但不能伪造已完成分类或生成', () => {
  const value = { ...answer, mode: 'LOCAL_EVIDENCE', status: 'SCOPE_UNAVAILABLE', evidence: [], parts: [
    { id: 1, question: '奖学金多少？', status: 'SCOPE_UNAVAILABLE', paragraphs: [], gaps: ['未获模型许可，问题未外发。'] }
  ] };
  assert.equal(answerView(value).status, 'SCOPE_UNAVAILABLE');
  for (const status of ['OUT_OF_SCOPE', 'SCOPE_UNKNOWN']) assert.throws(() => answerView({ ...value, status }), /格式/);
});
