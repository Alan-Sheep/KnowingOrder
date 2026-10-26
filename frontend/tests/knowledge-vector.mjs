import test from 'node:test';
import assert from 'node:assert/strict';
import { createEnterpriseClient, EnterpriseError } from '../src/enterprise.ts';

// 仅传输层合成契约；不调用浏览器、数据库、模型或Qdrant，不证明服务端授权。
const requestId = '00000000-0000-4000-8000-000000000001';
const otherId = '00000000-0000-4000-8000-000000000002';
const doc = '9007199254740993', version = '9007199254740994', revision = '9007199254740995';
const base = `/api/enterprises/1/knowledge-spaces/2/documents/${doc}`;
const jobs = `${base}/versions/${version}/vector-jobs`;
const publication = `${base}/vector-publication`;
const now = 1800000000000;
const job = { id: requestId, documentId: doc, versionId: version, expectedRevision: revision,
  status: 'QUEUED', expectedChunks: 17, verifiedChunks: 0, createdMs: now, deadlineMs: now + 120000,
  finishedMs: null, failureCode: null, message: '已受理，尚未发布。' };
const unpublished = { documentId: doc, jobId: null, revision: '0', status: 'UNPUBLISHED', updatedMs: null, message: '尚未发布。' };
const ready = { ...unpublished, jobId: requestId, revision: '1', status: 'READY', updatedMs: String(now), message: '代次已发布。' };
const withdrawn = { ...ready, jobId: null, revision: '2', status: 'WITHDRAWN', message: '已撤回。' };
const ok = data => Response.json({ code: 0, data });
const csrf = () => ok({ headerName: 'X-CSRF-TOKEN', token: 'synthetic-vector-only' });
const readJob = api => api.readVectorJob('1', '2', doc, version, requestId);
const readPublication = api => api.readVectorPublication('1', '2', doc);
const submit = api => api.submitVectorJob('1', '2', doc, version, requestId, revision);
const cancel = api => api.cancelVectorJob('1', '2', doc, version, requestId);
const publish = api => api.publishVectorIndex('1', '2', doc, requestId, '0');
const withdraw = api => api.withdrawVectorIndex('1', '2', doc, requestId, '1');
const methods = { submit, readJob, cancel, readPublication, publish, withdraw };

test('六入口保持精确路径、字段、Session、每次新CSRF；读取不受理或发布', async () => {
  const calls = [];
  let tokens = 0;
  const api = createEnterpriseClient(async (path, init) => {
    calls.push({ path, init });
    assert.equal(init.credentials, 'same-origin');
    assert.equal(init.cache, 'no-store');
    assert.equal(init.redirect, 'error');
    assert.ok(init.signal instanceof AbortSignal);
    if (path === '/api/auth/csrf') return ok({ headerName: 'X-CSRF-TOKEN', token: `synthetic-${++tokens}` });
    if (init.method === 'POST') {
      assert.deepEqual(init.headers, { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': `synthetic-${tokens}` });
      assert.equal(init.signal, calls.at(-2).init.signal, 'CSRF与本次写共享取消及总等待');
    }
    return ok(path.endsWith('/withdraw') ? withdrawn : path.endsWith('/publish') ? ready
      : path.endsWith('/vector-publication') ? unpublished : path.endsWith('/cancel')
        ? { ...job, status: 'CANCELLED', finishedMs: String(now + 1) } : job);
  });
  assert.deepEqual(await submit(api), job);
  assert.deepEqual(await readJob(api), job);
  await cancel(api);
  assert.deepEqual(await readPublication(api), unpublished);
  assert.deepEqual(await publish(api), ready);
  assert.deepEqual(await withdraw(api), withdrawn);
  const writes = calls.filter(call => call.init.method === 'POST');
  assert.equal(tokens, 4);
  assert.deepEqual(writes.map(call => [call.path, JSON.parse(call.init.body)]), [
    [jobs, { requestId, expectedRevision: revision }], [`${jobs}/${requestId}/cancel`, {}],
    [`${publication}/publish`, { jobId: requestId, expectedRevision: '0' }],
    [`${publication}/withdraw`, { expectedJobId: requestId, expectedRevision: '1' }]
  ]);
  assert.deepEqual(calls.filter(call => call.path !== '/api/auth/csrf' && !call.init.method).map(call => call.path),
    [`${jobs}/${requestId}`, publication]);
  api.dispose();
});

test('任务六态、完整核验和全部失败分类可读取，不把VERIFIED改成READY', async () => {
  const states = [job, { ...job, status: 'RUNNING' },
    { ...job, status: 'VERIFIED', verifiedChunks: 17, finishedMs: String(now + 1) },
    { ...job, status: 'CANCELLED', finishedMs: String(now + 1) },
    { ...job, status: 'TIMED_OUT', failureCode: 'TOTAL_TIMEOUT', finishedMs: String(now + 120000) }];
  for (const failureCode of ['ACCESS_REVOKED', 'REVISION_CONFLICT', 'MODEL_CHANGED', 'MANIFEST_MISMATCH',
    'WRITE_UNCONFIRMED', 'VECTOR_UNAVAILABLE', 'INTERNAL_ERROR', 'TOTAL_TIMEOUT', 'LEASE_LOST'])
    states.push({ ...job, status: 'FAILED', finishedMs: String(now + 1), failureCode });
  for (const data of states) {
    assert.deepEqual(await readJob(createEnterpriseClient(async () => ok(data))), data);
  }
  const verified = states[2];
  const api = createEnterpriseClient(async path => path.endsWith('/csrf') ? csrf() : ok(verified));
  assert.equal((await cancel(api)).status, 'VERIFIED', '取消已核验任务不伪装成撤回发布');
});

test('拒绝错任务、文档、版本、修订类型和模糊状态，禁止接受串接响应', async () => {
  for (const data of [null, [], {}, { ...job, id: otherId }, { ...job, documentId: '9' },
    { ...job, documentId: Number(doc) }, { ...job, versionId: '9' }, { ...job, expectedRevision: 7 },
    { ...job, expectedRevision: '0' }, { ...job, status: 'READY' }, { ...job, status: ['QUEUED'] },
    { ...job, message: '' }, { ...job, message: 'x'.repeat(301) }])
    await assert.rejects(readJob(createEnterpriseClient(async () => ok(data))), /格式或资源绑定/);
  const api = createEnterpriseClient(async path => path.endsWith('/csrf') ? csrf() : ok({ ...job, expectedRevision: '7' }));
  await assert.rejects(submit(api), error => error.uncertain && /核对/.test(error.message));
});

test('数量、首次截止、终态与失败分类不一致一律拒绝，未知响应不伪装成功', async () => {
  const verified = { ...job, status: 'VERIFIED', verifiedChunks: 17, finishedMs: String(now + 1) };
  for (const data of [{ ...job, expectedChunks: 0 }, { ...job, expectedChunks: 1001 }, { ...job, expectedChunks: '17' },
    { ...job, verifiedChunks: 1 }, { ...job, verifiedChunks: -1 }, { ...job, deadlineMs: now + 120001 },
    { ...job, createdMs: -1 }, { ...job, deadlineMs: '1800000120000' },
    { ...job, createdMs: Number.MAX_SAFE_INTEGER, deadlineMs: Number.MAX_SAFE_INTEGER + 120000 },
    { ...job, finishedMs: String(now + 1) }, { ...job, status: 'CANCELLED' },
    { ...job, status: 'CANCELLED', finishedMs: String(now - 1) },
    { ...job, status: 'CANCELLED', finishedMs: now + 1 }, { ...job, status: 'CANCELLED', finishedMs: '01800000000001' },
    { ...job, status: 'CANCELLED', finishedMs: '9223372036854775808' },
    { ...verified, verifiedChunks: 16 }, { ...verified, finishedMs: String(job.deadlineMs) },
    { ...verified, failureCode: 'INTERNAL_ERROR' },
    { ...job, failureCode: 'SQL_SECRET' }, { ...job, status: 'FAILED', finishedMs: String(now + 1) },
    { ...job, status: 'FAILED', finishedMs: String(now + 1), failureCode: ['LEASE_LOST'] },
    { ...job, status: 'TIMED_OUT', finishedMs: String(now + 1), failureCode: 'TOTAL_TIMEOUT' },
    { ...job, status: 'TIMED_OUT', finishedMs: String(job.deadlineMs), failureCode: 'LEASE_LOST' }])
    await assert.rejects(readJob(createEnterpriseClient(async () => ok(data))), /格式或资源绑定/);
});

test('索引四态保留原语义，读取Long最大修订不先转成Number', async () => {
  for (const data of [unpublished, ready, { ...ready, status: 'STALE' }, withdrawn,
    { ...ready, revision: '9223372036854775807' }])
    assert.deepEqual(await readPublication(createEnterpriseClient(async () => ok(data))), data);
  const api = createEnterpriseClient(async path => path.endsWith('/csrf') ? csrf() : ok({ ...withdrawn, revision: '1' }));
  assert.equal((await api.withdrawVectorIndex('1', '2', doc, null, '0')).revision, '1');
});

test('拒绝矛盾指针、空的已发布编号、数值修订和错文档，不从旧代次回退', async () => {
  for (const data of [null, {}, { ...ready, documentId: '8' }, { ...ready, revision: 1 }, { ...ready, status: ['READY'] },
    { ...ready, jobId: null }, { ...ready, jobId: 'bad' }, { ...ready, revision: '0' },
    { ...ready, updatedMs: now }, { ...ready, updatedMs: null }, { ...ready, updatedMs: '01' },
    { ...unpublished, jobId: requestId }, { ...unpublished, revision: '1' }, { ...unpublished, updatedMs: String(now) },
    { ...withdrawn, jobId: requestId }, { ...ready, status: 'VERIFIED' }])
    await assert.rejects(readPublication(createEnterpriseClient(async () => ok(data))), /格式或资源绑定/);
});

test('发布和撤回精确验证修订递增及返回任务，回执格式错误保留未知结果', async () => {
  for (const data of [{ ...ready, jobId: otherId }, { ...ready, revision: '2' }, { ...ready, status: 'STALE' }]) {
    const api = createEnterpriseClient(async path => path.endsWith('/csrf') ? csrf() : ok(data));
    await assert.rejects(publish(api), error => error.uncertain && /核对/.test(error.message));
  }
  for (const data of [ready, { ...withdrawn, revision: '3' }]) {
    const api = createEnterpriseClient(async path => path.endsWith('/csrf') ? csrf() : ok(data));
    await assert.rejects(withdraw(api), error => error.uncertain);
  }
  const api = createEnterpriseClient(async path => path.endsWith('/csrf') ? csrf() : ok({ ...ready, revision: '9007199254740994' }));
  assert.equal((await api.publishVectorIndex('1', '2', doc, requestId, '9007199254740993')).revision, '9007199254740994');
});

test('非法编号、UUID注入和非法字符串修订在取CSRF前拒绝', async () => {
  let calls = 0;
  const api = createEnterpriseClient(async () => { calls++; return ok(job); });
  for (const value of [null, 1, '', '0', '-1', '01', '1.0', '1e2', ' 1', '9223372036854775808', '../2', "' OR 1=1 --"]) {
    await assert.rejects(api.submitVectorJob('1', '2', doc, version, requestId, value));
    await assert.rejects(api.readVectorJob(value, '2', doc, version, requestId));
    await assert.rejects(api.readVectorJob('1', value, doc, version, requestId));
    await assert.rejects(api.readVectorJob('1', '2', value, version, requestId));
    await assert.rejects(api.readVectorJob('1', '2', doc, value, requestId));
    await assert.rejects(api.readVectorPublication('1', '2', value));
  }
  for (const value of [null, 1, '', '../cancel', requestId.toUpperCase().replace('00000000', 'ABCDEF00'), "' OR 1=1 --"]) {
    await assert.rejects(api.readVectorJob('1', '2', doc, version, value));
    await assert.rejects(api.cancelVectorJob('1', '2', doc, version, value));
    await assert.rejects(api.publishVectorIndex('1', '2', doc, value, '0'));
  }
  for (const value of [null, 0, -1, '', '-1', '01', '1.5', '9223372036854775807', '9223372036854775808']) {
    await assert.rejects(api.publishVectorIndex('1', '2', doc, requestId, value));
    await assert.rejects(api.withdrawVectorIndex('1', '2', doc, null, value));
  }
  assert.equal(calls, 0);
});

test('任务修订允许Long最大值；发布写入上界减一，响应允许到达Long最大值', async () => {
  const api = createEnterpriseClient(async (path, init) => {
    if (path.endsWith('/csrf')) return csrf();
    const body = JSON.parse(init.body);
    if (path.endsWith('/vector-jobs')) return ok({ ...job, expectedRevision: body.expectedRevision });
    assert.equal(body.expectedRevision, '9223372036854775806');
    return ok({ ...ready, revision: '9223372036854775807' });
  });
  assert.equal((await api.submitVectorJob('1', '2', doc, version, requestId, '9223372036854775807')).expectedRevision, '9223372036854775807');
  assert.equal((await api.publishVectorIndex('1', '2', doc, requestId, '9223372036854775806')).revision, '9223372036854775807');
});

test('公开投影丢弃意外内部字段；不把摘要、租约或模型元数据传给页面', async () => {
  const hidden = { generation: 'internal', leaseToken: 'internal', modelName: 'internal', storageKey: 'internal' };
  assert.deepEqual(await readJob(createEnterpriseClient(async () => ok({ ...job, ...hidden }))), job);
  assert.deepEqual(await readPublication(createEnterpriseClient(async () => ok({ ...ready, ...hidden }))), ready);
});

for (const [name, invoke] of Object.entries(methods)) {
  const writing = !name.startsWith('read');
  test(`${name}：业务错误和HTTP故障只返回安全提示，无自动重试`, async () => {
    for (const [http, code] of [[200, 400], [200, 401], [200, 404], [200, 409], [200, 503], [200, 504],
      [401, 401], [403, 403], [404, 404], [413, 413], [429, 429], [502, 500]]) {
      let requests = 0;
      const api = createEnterpriseClient(async path => {
        if (path.endsWith('/csrf')) return csrf();
        requests++;
        return Response.json({ code, message: 'SECRET_SQL_PASSWORD_TOKEN' }, { status: http });
      });
      await assert.rejects(invoke(api), error => error instanceof EnterpriseError && !error.message.includes('SECRET')
        && error.code === (http === 200 ? code : http) && error.uncertain === (writing && (http >= 500 || (http === 200 && code >= 500))));
      assert.equal(requests, 1);
    }
  });
  test(`${name}：空响应及格式变更停止操作，写入结果保留未知`, async () => {
    for (const response of [() => ok(null), () => Response.json({}), () => new Response('<html>SECRET</html>')]) {
      let requests = 0;
      const api = createEnterpriseClient(async path => {
        if (path.endsWith('/csrf')) return csrf();
        requests++; return response();
      });
      await assert.rejects(invoke(api), error => error instanceof EnterpriseError && error.uncertain === writing && !error.message.includes('SECRET'));
      assert.equal(requests, 1);
    }
  });
}

const blocked = (_path, init) => new Promise((_resolve, reject) => {
  if (init.signal.aborted) reject(new DOMException('合成取消', 'AbortError'));
  else init.signal.addEventListener('abort', () => reject(new DOMException('合成取消', 'AbortError')), { once: true });
});

test('总等待包含CSRF；发出写入前超时不标记已写，CSRF错误不提交任务', async () => {
  for (const invoke of [submit, cancel, publish, withdraw]) {
    let calls = 0;
    const api = createEnterpriseClient((path, init) => { calls++; return blocked(path, init); }, 15);
    await assert.rejects(invoke(api), error => !error.uncertain && /停止连接/.test(error.message));
    assert.equal(calls, 1);
    for (const data of [null, {}, { headerName: 'Authorization', token: 'SECRET' }, { headerName: 'X-CSRF-TOKEN', token: '' }]) {
      let writes = 0;
      const broken = createEnterpriseClient(async (_path, init) => { if (init.method === 'POST') writes++; return ok(data); });
      await assert.rejects(invoke(broken), error => !error.uncertain && !error.message.includes('SECRET'));
      assert.equal(writes, 0);
    }
  }
});

test('写入超时或断网不自动重试；同编号读取可核对，取消不承诺回滚', async () => {
  for (const invoke of [submit, cancel, publish, withdraw]) {
    let writes = 0;
    const api = createEnterpriseClient((path, init) => {
      if (path.endsWith('/csrf')) return Promise.resolve(csrf());
      if (init.method === 'POST') { writes++; return blocked(path, init); }
      return Promise.resolve(ok(path.endsWith('/vector-publication') ? ready : job));
    }, 15);
    await assert.rejects(invoke(api), error => error.uncertain && /结果未知/.test(error.message));
    assert.equal(writes, 1);
    await readJob(api); await readPublication(api);
    assert.equal(writes, 1);
  }
  let writes = 0;
  const api = createEnterpriseClient(async path => {
    if (path.endsWith('/csrf')) return csrf();
    writes++; throw new TypeError('SECRET_CONNECTION');
  });
  await assert.rejects(submit(api), error => error.uncertain && !error.message.includes('SECRET'));
  assert.equal(writes, 1);
});

test('人工同UUID重传不换参数不续截止；同预期发布回执核对仍返回同修订', async () => {
  const bodies = [];
  const api = createEnterpriseClient(async (path, init) => {
    if (path.endsWith('/csrf')) return csrf();
    if (init.method === 'POST') bodies.push(JSON.parse(init.body));
    return ok(path.endsWith('/publish') ? ready : job);
  });
  const first = await submit(api); const replay = await submit(api);
  assert.deepEqual(replay, first); assert.deepEqual(bodies[0], bodies[1]);
  assert.equal(replay.deadlineMs, now + 120000);
  assert.deepEqual(await publish(api), await publish(api));
  assert.deepEqual(bodies[2], bodies[3]);
});

test('dispose中断在途查询与写入，不调用取消接口或重放；并发各用独立取消信号', async () => {
  const seen = [];
  const api = createEnterpriseClient((path, init) => {
    if (path.endsWith('/csrf')) return Promise.resolve(csrf());
    seen.push({ path, signal: init.signal }); return blocked(path, init);
  });
  const a = readJob(api); const b = publish(api);
  const checks = [assert.rejects(a, error => !error.uncertain), assert.rejects(b, error => error.uncertain)];
  await new Promise(resolve => setImmediate(resolve));
  api.dispose(); await Promise.all(checks);
  assert.equal(seen.length, 2);
  assert.notEqual(seen[0].signal, seen[1].signal);
  assert.ok(seen.every(call => call.signal.aborted && !call.path.endsWith('/cancel')));
});
