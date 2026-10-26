import test from 'node:test';
import assert from 'node:assert/strict';
import { departmentsView, memberView, membersView, departmentResult, organizationVersion, departmentName } from '../src/organization.ts';
import { createEnterpriseClient } from '../src/enterprise.ts';

const department = { id: '11', name: '研发', status: 0 };
const member = { id: '101', userId: '25', username: 'synthetic_employee', departmentId: '11', role: 'EMPLOYEE', status: 0, accountStatus: 0, permissionVersion: 1 };
const page = rows => ({ records: rows, total: rows.length, pageNum: 1, pageSize: 20, pages: rows.length ? 1 : 0 });
const ok = data => Response.json({ code: 0, data });
test('部门和成员契约保留字符串编号，拒绝非法状态、空值和越界版本', () => {
  assert.deepEqual(memberView(member), member);
  assert.deepEqual(departmentsView({ enterprisePermissionVersion: 1, page: page([department]) }).page.records, [department]);
  for (const v of [null, [], {}, { ...member, id: 101 }, { ...member, departmentId: '' }, { ...member, status: '0' },
    { ...member, role: 'SUPER_ADMIN' }, { ...member, accountStatus: 2 }, { ...member, permissionVersion: Number.MAX_SAFE_INTEGER + 1 }]) assert.throws(() => memberView(v));
  for (const v of [null, 0, 1.1, '1', Number.MAX_SAFE_INTEGER + 1]) assert.throws(() => organizationVersion(v));
  assert.throws(() => membersView({ page: { ...page([member]), pageSize: 101 } }));
  assert.throws(() => departmentResult({ enterprisePermissionVersion: 1, department: { ...department, status: true } }));
});
test('前端成员白名单丢弃响应中意外附带的密码和认证字段', () => {
  assert.deepEqual(memberView({ ...member, password: 'synthetic-only', authVersion: 2, passwordHash: 'synthetic-hash' }), member);
  assert.equal(JSON.stringify(memberView({ ...member, password: 'synthetic-only' })).includes('password'), false);
});
test('名称边界拒绝控制字符，HTML和SQL字符只作为文本保留', () => {
  for (const name of ['', ' ', '字'.repeat(129), '研发\n部门', '研发\u0000', '研发\u2028部门']) assert.throws(() => departmentName(name));
  assert.equal(departmentName(" 研发' <script> "), "研发' <script>");
});
test('组织读写沿用Session和CSRF，写入携带版本和明确清空部门语义', async () => {
  const calls = [];
  const api = createEnterpriseClient(async (path, init) => {
    calls.push({ path, init }); assert.equal(init.credentials, 'same-origin'); assert.equal(init.cache, 'no-store');
    if (path.endsWith('/csrf')) return ok({ headerName: 'X-CSRF-TOKEN', token: 'synthetic-only' });
    if (init.method === 'POST') assert.equal(init.headers['X-CSRF-TOKEN'], 'synthetic-only');
    if (path.endsWith('/members/101')) return ok({ ...member, departmentId: null, permissionVersion: 2 });
    if (path.endsWith('/employees')) return ok({ userId: '25', memberId: '101', enterpriseId: '1' });
    if (path.includes('/departments')) return ok(init.method === 'POST' ? { enterprisePermissionVersion: 2, department } : { enterprisePermissionVersion: 1, page: page([department]) });
    return ok({ page: page([member]) });
  });
  await api.organizationDepartments('1'); await api.organizationMembers('1');
  await api.createDepartment('1', 1, '研发'); await api.changeDepartment('1', '11', 1, '研发', 1);
  await api.changeOrganizationMember('1', '101', 1, null, 0);
  await api.createEmployee('1', 'test_employee', 'synthetic-password-only');
  const writes = calls.filter(c => c.init.method === 'POST'); assert.equal(writes.length, 4);
  assert.deepEqual(JSON.parse(writes[0].init.body), { expectedEnterpriseVersion: 1, name: '研发' });
  assert.deepEqual(JSON.parse(writes[2].init.body), { expectedVersion: 1, departmentId: null, status: 0 });
});
test('写结果丢失不自动重试；重新读取组织不产生POST', async () => {
  let writes = 0;
  const api = createEnterpriseClient(async (path, init) => {
    if (path.endsWith('/csrf')) return ok({ headerName: 'X-CSRF-TOKEN', token: 'synthetic-only' });
    if (init.method === 'POST') { writes++; throw new TypeError('合成断网'); }
    return ok({ enterprisePermissionVersion: 2, page: page([department]) });
  });
  await assert.rejects(api.createDepartment('1', 1, '研发'), e => e.uncertain);
  assert.equal(writes, 1); await api.organizationDepartments('1'); assert.equal(writes, 1);
});
test('非法输入在网络调用前拒绝', async () => {
  let calls = 0; const api = createEnterpriseClient(async () => { calls++; return ok(null); });
  await assert.rejects(api.createDepartment('1', 0, '研发'));
  await assert.rejects(api.changeDepartment('1', '11', 1, '研发', 2));
  await assert.rejects(api.changeOrganizationMember('1', '101', 1, '1 OR 1=1', 0));
  await assert.rejects(api.changeOrganizationMember('1', '101', 1, null, true));
  await assert.rejects(api.createEmployee('1', 'ok_user', 'too-short'));
  await assert.rejects(api.createEmployee('1', 'ok_user', '中'.repeat(25)));
  assert.equal(calls, 0);
});
test('写入成功但响应契约变更标为结果未知，不使用异常原文', async () => {
  let writes = 0;
  const api = createEnterpriseClient(async (path) => {
    if (path.endsWith('/csrf')) return ok({ headerName: 'X-CSRF-TOKEN', token: 'synthetic-only' });
    writes++; return ok({ token: 'synthetic-not-a-secret', status: 'incompatible' });
  });
  await assert.rejects(api.createDepartment('1', 1, '研发'), e => e.uncertain && !e.message.includes('token'));
  assert.equal(writes, 1);
});
test('慢请求遵守等待上限，服务器异常正文不传给页面', async () => {
  const slow = createEnterpriseClient(async (_path, init) => new Promise((resolve, reject) => {
    init.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
  }), 10);
  await assert.rejects(slow.organizationDepartments('1'), e => !e.uncertain && e.message.includes('等待超过'));
  const failed = createEnterpriseClient(async () => Response.json({ code: 500, message: 'PASSWORD_TOKEN_SECRET_SQL' }));
  await assert.rejects(failed.organizationMembers('1'), e => !e.message.includes('SECRET'));
});
