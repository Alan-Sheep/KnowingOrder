import test from 'node:test';
import assert from 'node:assert/strict';
import { createEnterpriseClient, positiveId } from '../src/enterprise.ts';

const ok = data => Response.json({ code: 0, data });
const csrf = token => ok({ headerName: 'X-CSRF-TOKEN', token });
const fixture = { id: '9007199254740993', name: '研发知识库', permissionVersion: 1 };

test('登录企业归属保留大编号、拒绝重复与伪造角色，不把其他账号字段传到页面', async () => {
  const enterprise = { enterpriseId: fixture.id, enterpriseName: '虚构企业', role: 'EMPLOYEE' };
  const current = { userId: '25', enterprises: [enterprise] };
  assert.deepEqual(await createEnterpriseClient(async () => ok({ ...current, passwordHash: '不应流入页面' })).me(), current);
  assert.deepEqual(await createEnterpriseClient(async () => ok({ userId: '25', enterprises: [] })).me(), { userId: '25', enterprises: [] });
  for (const enterprises of [null, {}, [null], [enterprise, enterprise], [{ ...enterprise, enterpriseId: 1 }],
    [{ ...enterprise, role: 'ROOT' }], [{ ...enterprise, enterpriseName: '' }], Array(101).fill(enterprise)])
    await assert.rejects(createEnterpriseClient(async () => ok({ userId: '25', enterprises })).me(), /企业归属响应格式/);
});

test('空间列表严格校验分页与返回结构，保留Long字符串且不自动重试', async () => {
  const page = { records: [fixture], total: 1, pageNum: 1, pageSize: 20, pages: 1 };
  let calls = 0;
  const api = createEnterpriseClient(async path => {
    calls++;
    assert.equal(path, '/api/enterprises/1/knowledge-spaces?pageNum=1&pageSize=20');
    return ok(page);
  });
  assert.deepEqual(await api.listSpaces('1'), page);
  for (const [number, size] of [[0, 20], [1, 501], [1.1, 1], [Number.MAX_SAFE_INTEGER + 1, 1]])
    await assert.rejects(api.listSpaces('1', number, size), /页码/);
  assert.equal(calls, 1);
  for (const data of [null, {}, { ...page, total: '1' }, { ...page, records: [null] }, { ...page, pages: -1 }])
    await assert.rejects(createEnterpriseClient(async () => ok(data)).listSpaces('1'), /格式/);
});

test('编号保持字符串，不丢失Long精度；非法编号及越界先于网络拒绝', async () => {
  for (const value of ['', '0', '-1', '1.1', '01', '1/other', '9223372036854775808', 1, null]) assert.equal(positiveId(value), false);
  assert.equal(positiveId('9223372036854775807'), true);
  let calls = 0;
  const api = createEnterpriseClient(async () => { calls++; return ok(fixture); });
  for (const id of ['0', '../2', '9223372036854775808']) await assert.rejects(api.readSpace(id, '1'), /编号/);
  for (const name of ['', ' ', '字'.repeat(129)]) await assert.rejects(api.createSpace('1', name), /名称/);
  assert.equal(calls, 0);
});

test('登录使用表单与Cookie，创建使用登录后新CSRF；不携带客户端角色和成员编号', async () => {
  const requests = [];
  let token = 0;
  const api = createEnterpriseClient(async (path, init) => {
    requests.push({ path, init });
    assert.equal(init.credentials, 'same-origin');
    assert.equal(init.cache, 'no-store');
    assert.equal(init.redirect, 'error');
    if (path.endsWith('/csrf')) return csrf(`csrf-${++token}`);
    if (path.endsWith('/me') || path.endsWith('/login')) return ok({ userId: '25' });
    if (path.endsWith('/logout')) return ok(null);
    return ok(fixture);
  });
  assert.deepEqual(await api.login('admin', 'fictitious-test-only'), { userId: '25' });
  assert.deepEqual(await api.createSpace('1', ' 研发知识库 '), fixture);
  assert.deepEqual(await api.readSpace('1', fixture.id), fixture);
  await api.logout();
  assert.equal(token, 3);
  const writes = requests.filter(r => r.init.method === 'POST');
  assert.equal(writes.length, 3);
  assert.equal(writes[0].init.headers['X-CSRF-TOKEN'], 'csrf-1');
  assert.equal(new URLSearchParams(writes[0].init.body).get('password'), 'fictitious-test-only');
  assert.equal(writes[1].init.headers['X-CSRF-TOKEN'], 'csrf-2');
  assert.deepEqual(JSON.parse(writes[1].init.body), { name: '研发知识库' });
  assert.equal(writes[2].init.headers['X-CSRF-TOKEN'], 'csrf-3');
});

test('HTTP200业务失败、401/403、宕机及格式变化显示安全提示，不泄露上游正文', async () => {
  for (const [status, code, expected] of [[200, 404, /不存在或不可访问/], [200, 401, /登录/],
    [200, 400, /输入/], [401, 401, /登录/], [403, 403, /校验/], [429, 429, /频繁/], [503, 500, /暂不可用/]]) {
    let calls = 0;
    const api = createEnterpriseClient(async () => { calls++; return Response.json({ code, message: 'SECRET_SQL_TOKEN' }, { status }); });
    await assert.rejects(api.readSpace('1', '2'), error => expected.test(error.message) && !error.message.includes('SECRET'));
    assert.equal(calls, 1);
  }
  for (const data of [null, {}, { ...fixture, id: 1 }, { ...fixture, permissionVersion: '1' }, { ...fixture, name: '' }]) {
    await assert.rejects(createEnterpriseClient(async () => ok(data)).readSpace('1', '2'), /格式/);
  }
});

test('登录前令牌异常就停止，不提交密码；CSRF失败不自动重试', async () => {
  for (const value of [null, {}, { headerName: 'Authorization', token: 'x' }, { headerName: 'X-CSRF-TOKEN', token: '' }]) {
    let calls = 0;
    const api = createEnterpriseClient(async () => { calls++; return ok(value); });
    await assert.rejects(api.login('admin', 'test-only'), /凭据格式/);
    assert.equal(calls, 1);
  }
  let calls = 0;
  const api = createEnterpriseClient(async path => {
    calls++;
    return path.endsWith('/csrf') ? csrf('fresh') : new Response(null, { status: 403 });
  });
  await assert.rejects(api.createSpace('1', '研发'), /校验/);
  assert.equal(calls, 2);
});

const blocked = (_path, init) => new Promise((_resolve, reject) => {
  if (init.signal.aborted) reject(new DOMException('取消', 'AbortError'));
  else init.signal.addEventListener('abort', () => reject(new DOMException('取消', 'AbortError')), { once: true });
});

test('整个操作超时包含CSRF阶段；未发写入可以安全停止，已发写入明确结果未知', async () => {
  let calls = 0;
  const beforeWrite = createEnterpriseClient((path, init) => { calls++; return blocked(path, init); }, 20);
  await assert.rejects(beforeWrite.createSpace('1', '研发'), error => !error.uncertain && /停止连接/.test(error.message));
  assert.equal(calls, 1);
  const afterWrite = createEnterpriseClient((path, init) => {
    calls++;
    return path.endsWith('/csrf') ? Promise.resolve(csrf('fresh')) : blocked(path, init);
  }, 20);
  await assert.rejects(afterWrite.createSpace('1', '研发'), error => error.uncertain && /结果未知/.test(error.message));
  assert.equal(calls, 3);
});

test('写入返回空/错误格式/5xx保留未知状态，读取断网和主动结束不重发', async () => {
  for (const response of [() => ok(null), () => Response.json({}), () => new Response(null, { status: 502 })]) {
    let calls = 0;
    const api = createEnterpriseClient(async path => { calls++; return path.endsWith('/csrf') ? csrf('fresh') : response(); });
    await assert.rejects(api.createSpace('1', '研发'), error => error.uncertain && /核对/.test(error.message));
    assert.equal(calls, 2);
  }
  const offline = createEnterpriseClient(async () => { throw new TypeError('secret backend details'); });
  await assert.rejects(offline.me(), /连接已中断/);
  const api = createEnterpriseClient(blocked);
  const pending = api.me();
  api.dispose();
  await assert.rejects(pending, /连接已中断/);
});
