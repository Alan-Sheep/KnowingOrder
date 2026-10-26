import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

/*
 * 登录态共享回归：
 * ① 冷启动只问一次 /api/auth/me；
 * ② 四个页面来回切换、组件反复重建，仍然不再请求；
 * ③ 在一个页面登出，切到另一个页面必须已经同步为未登录，而不是继续显示旧数据。
 * 全程拦截接口，不访问真实后端。
 */
const base = process.env.AUDIT_BASE ?? 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';

const account = { userId: '1', enterprises: [{ enterpriseId: '1', enterpriseName: '知序科技', role: 'ADMIN' }] };
const pageOf = records => ({ code: 0, data: { records, total: records.length, pageNum: 1, pageSize: 20, pages: 1 } });
const spacePage = { code: 0, data: { records: [{ id: '1', name: '差旅报销与费用制度', permissionVersion: 5 }], total: 1, pageNum: 1, pageSize: 10, pages: 1 } };

let loggedIn = false;
const requestLog = [];

const profile = await mkdtemp(join(tmpdir(), 'session-'));
const browser = spawn(browserPath, ['--headless=new', '--disable-gpu', '--no-first-run', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore', windowsHide: true });
let socket;
try {
  let port;
  for (let index = 0; index < 150; index++) {
    try { port = Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); break; } catch { await delay(100); }
  }
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  let nextId = 0;
  const pending = new Map();
  const cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId;
    const timer = setTimeout(() => reject(new Error(`超时：${method}`)), 20000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params }));
  });
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    if (message.id) {
      const item = pending.get(message.id);
      if (!item) return;
      clearTimeout(item.timer);
      pending.delete(message.id);
      message.error ? item.reject(new Error(message.error.message)) : item.resolve(message.result);
      return;
    }
    if (message.method !== 'Fetch.requestPaused') return;
    const path = new URL(message.params.request.url).pathname;
    requestLog.push(path);
    let payload = { code: 0, data: null };
    if (path === '/api/auth/me') payload = loggedIn ? { code: 0, data: account } : { code: 401, message: '未登录' };
    else if (path === '/api/auth/login') { loggedIn = true; payload = { code: 0, data: account }; }
    else if (path === '/api/auth/logout') { loggedIn = false; }
    else if (path === '/api/auth/csrf') payload = { code: 0, data: { headerName: 'X-CSRF-TOKEN', token: 'csrf-session' } };
    else if (/\/knowledge-spaces$/.test(path)) payload = spacePage;
    else if (/\/affairs\/rules$/.test(path)) payload = { code: 0, data: pageOf([]) };
    else if (/\/(confirmations|applications)$/.test(path)) payload = { code: 0, data: pageOf([]) };
    else if (/\/members$/.test(path)) payload = { code: 0, data: pageOf([]) };
    else if (/\/workspace\/chats$/.test(path)) payload = { code: 0, data: [] };
    else if (/answers\/mode$/.test(path)) payload = { code: 0, data: 'SYNTHETIC_MODEL' };
    else if (/answers\/retrieval-mode$/.test(path)) payload = { code: 0, data: 'HYBRID' };
    cdp('Fetch.fulfillRequest', {
      requestId: message.params.requestId, responseCode: 200,
      responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
      body: Buffer.from(JSON.stringify(payload)).toString('base64')
    }).catch(() => {});
  });

  const evaluate = async expression => {
    const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? '执行失败');
    return result.result.value;
  };
  const wait = async (expression, label = expression) => {
    for (let index = 0; index < 240; index++) { if (await evaluate(expression)) return; await delay(50); }
    throw new Error(`等待超时：${label}`);
  };
  const click = text => evaluate(`([...document.querySelectorAll('button')].find(node => node.textContent.trim().includes(${JSON.stringify(text)})))?.click()`);
  const meCount = () => requestLog.filter(path => path === '/api/auth/me').length;
  const onPage = name => wait(`document.querySelector('.breadcrumb strong')?.textContent.trim() === ${JSON.stringify(name)}`, `停在${name}`);

  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

  // 先以未登录状态进来并完成一次登录，得到真实登录态。
  await cdp('Page.navigate', { url: base });
  await wait('document.querySelector(".overview-empty") !== null', '工作台未登录提示');
  // 未登录访客只核对一次身份。
  assert.equal(meCount(), 1, `未登录冷启动应只问一次 /api/auth/me，实际 ${meCount()} 次：${requestLog.join(' ')}`);
  await click('用企业账号登录');
  await wait('document.querySelector(".account-form") !== null', '登录表单');
  await evaluate(`(() => {
    const fill = (selector, value) => { const node = document.querySelector(selector); node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })); };
    fill('#enterprise-username', 'zhanglan');
    fill('#enterprise-password', 'Passw0rd!');
    document.querySelector('.account-form').requestSubmit();
    return true;
  })()`);
  await wait('document.querySelector(".work-overview") !== null', '登录后回到工作台');
  // 登录接口自身会回读一次账号，属于登录动作的一部分；此后的基线是 2。
  const afterLogin = meCount();

  // 四个页面来回切，组件每次都会重建，但不应再问一次。
  for (const name of ['知识空间', '员工事务', '企业问答', '工作台', '知识空间']) {
    await click(name);
    await onPage(name);
    await delay(150);
  }
  assert.equal(meCount(), afterLogin, `切页后又请求了 /api/auth/me，共 ${meCount()} 次`);

  // 在知识空间登出，切到员工事务必须已经同步为未登录。
  await wait('document.querySelector(".account-line") !== null', '知识空间已登录');
  await click('退出登录');
  await wait('document.querySelector(".account-form") !== null', '知识空间登出后回到登录表单');
  await click('员工事务');
  await onPage('员工事务');
  const staleForm = await evaluate(`document.querySelector('.account-form') !== null`);
  assert.ok(staleForm, '在知识空间登出后，员工事务仍显示着登录后的旧界面');
  assert.equal(meCount(), afterLogin, '登出后不应再为确认身份请求 /api/auth/me');

  console.log(`session-check 通过：全流程仅 ${afterLogin} 次 /api/auth/me，登出跨页同步`);
} finally {
  try { socket?.close(); } catch { /* 关闭失败不影响结论 */ }
  browser.kill();
}
