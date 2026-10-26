import assert from 'node:assert/strict';
import { test } from 'node:test';
import { scoreCase, metrics } from './live-acceptance.mjs';

const sample = { id: '拒答边界', keywords: ['事务', '回滚'], expectSources: ['mysql.com'], shouldRefuse: true };
const answer = (status, url = 'https://dev.mysql.com/doc/') => ({ status: 'NO_ANSWER', requestId: 'test', elapsedMillis: 1,
  answers: [{ status, answer: '事务', sources: [{ url }] }] });

test('网络失败、超时、空分项不能伪装成正确拒答', () => {
  for (const value of [undefined, { answers: [] }, answer('FAILED'), answer('TIMED_OUT')]) {
    assert.equal(scoreCase(sample, value).refusedByContract, false);
  }
  assert.equal(scoreCase(sample, answer('INSUFFICIENT_EVIDENCE')).refusedByContract, true);
});

test('来源按域名边界匹配，拒绝伪子域名和凭空命中', () => {
  assert.equal(scoreCase(sample, answer('ANSWERED')).expectedSourceHit, true);
  for (const url of ['https://mysql.com.evil.example', 'https://notmysql.com', 'bad-url']) {
    assert.equal(scoreCase(sample, answer('ANSWERED', url)).expectedSourceHit, false);
  }
});

test('失败保留在原分母，关键词和来源分别统计', () => {
  const rows = [scoreCase(sample, answer('INSUFFICIENT_EVIDENCE')), scoreCase(sample, undefined)];
  assert.deepEqual(metrics(rows), { retrievalOnSourceCases: 0.5, sourceHitRate: 0.5,
    keywordCoverage: 0.25, refusalByContract: 0.5, requestFailures: 1 });
});
