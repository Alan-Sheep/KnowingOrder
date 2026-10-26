import assert from 'node:assert/strict';

// 一次性授权脚本：给指定知识空间的全部部门授 READ。用法：
// ADMIN_USERNAME=zhixuadmin ADMIN_PASSWORD='..' node scripts/authorize-org-spaces.mjs --base=http://.. --enterprise-id=1
const baseUrl = process.argv.find(v => v.startsWith('--base='))?.split('=')[1]?.replace(/\/$/, '');
const enterpriseId = process.argv.find(v => v.startsWith('--enterprise-id='))?.split('=')[1];
assert.ok(baseUrl && enterpriseId, '需要 --base= 与 --enterprise-id=');
const accountName = process.env.ADMIN_USERNAME ?? 'zhixuadmin';
const accountPassword = process.env.ADMIN_PASSWORD;
assert.ok(accountPassword, '请设置 ADMIN_PASSWORD');

const cookies = new Map();
const cookieHeader = () => [...cookies].map(([n, v]) => `${n}=${v}`).join('; ');
function remember(response) {
  for (const h of response.headers.getSetCookie()) { const p = h.split(';')[0], i = p.indexOf('='); if (i > 0) cookies.set(p.slice(0, i), p.slice(i + 1)); }
}
async function send(path, init = {}) {
  const response = await fetch(baseUrl + path, { ...init, redirect: 'manual', headers: { ...(init.headers ?? {}), cookie: cookieHeader() }, signal: AbortSignal.timeout(30000) });
  remember(response); return response;
}
async function token() { const r = await send('/api/auth/csrf'); return (await r.json()).data; }
async function login() {
  cookies.clear();
  const value = await token();
  const r = await send('/api/auth/login', { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded', [value.headerName]: value.token }, body: new URLSearchParams({ username: accountName, password: accountPassword }) });
  assert.equal((await r.json()).code, 0, '登录失败');
}
async function request(path, body) {
  const options = {};
  if (body !== undefined) { const value = await token(); options.method = 'POST'; options.headers = { [value.headerName]: value.token, 'Content-Type': 'application/json' }; options.body = JSON.stringify(body); }
  let response = await send(path, options);
  for (let attempt = 0; response.status === 401 && attempt < 2; attempt++) { await login(); response = await send(path, options); }
  return { http: response.status, body: await response.json().catch(() => ({})) };
}
await login();
const root = `/api/enterprises/${enterpriseId}`;
const spaces = [];
for (let page = 1; page <= 5; page++) {
  const { http, body } = await request(`${root}/knowledge-spaces?pageNum=${page}&pageSize=100`);
  assert.equal(http, 200);
  const value = body.data.page ?? body.data;
  spaces.push(...value.records);
  if (page >= value.pages) break;
}
const { body: deptBody } = await request(`${root}/organization/departments?pageNum=1&pageSize=100`);
const departments = deptBody.data.page.records.filter(d => d.status === 0);
console.log(`空间 ${spaces.length} 个，部门 ${departments.length} 个`);
let granted = 0, skipped = 0;
for (const space of spaces) {
  const { body: existing } = await request(`${root}/knowledge-spaces/${space.id}/grants`);
  const has = new Set((existing.data ?? []).map(g => `${g.targetType}:${g.targetId}`));
  let version = space.permissionVersion;
  for (const dept of departments) {
    if (has.has(`DEPARTMENT:${dept.id}`)) { skipped++; continue; }
    for (let attempt = 0; attempt < 4; attempt++) {
      const { http, body } = await request(`${root}/knowledge-spaces/${space.id}/grants`,
        { expectedVersion: version, targetType: 'DEPARTMENT', targetId: dept.id, permission: 'READ', revoke: false });
      if (http === 200 && body.code === 0) { version = body.data.permissionVersion; granted++; break; }
      if (body.code === 409 || http === 409) { const fresh = await request(`${root}/knowledge-spaces?pageNum=1&pageSize=100`); version = (fresh.body.data.page ?? fresh.body.data).records.find(s => String(s.id) === String(space.id)).permissionVersion; continue; }
      assert.fail(`授权失败：${space.name} → ${dept.name} HTTP${http} code=${body.code} ${body.message ?? ''}`);
    }
  }
  console.log(`${space.name}（#${space.id}）：部门授权完成`);
}
console.log(`完成：新授权 ${granted} 条，已有跳过 ${skipped} 条`);
