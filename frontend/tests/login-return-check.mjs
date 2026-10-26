import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

/*
 * 登录回流回归：
 * ① 工作台未登录 → 点登录 → 知识空间 → 登录成功必须回到工作台；
 * ② 企业问答未登录 → 点登录 → 知识空间 → 登录成功必须回到企业问答；
 * ③ 本来就在知识空间登录 → 不许被弹走；
 * ④ 登录失败不许回跳（否则用户会被弹回来源页又进不去）。
 * 全程拦截接口，不访问真实后端。
 */
const base = process.env.AUDIT_BASE ?? 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outDir = process.env.UI_SCREENSHOT_DIR ?? './test-artifacts/audit';
const prefix = process.env.AUDIT_PREFIX ?? 'login';
const width = Number(process.env.AUDIT_WIDTH ?? 1440);
const height = Number(process.env.AUDIT_HEIGHT ?? 900);

const account = { userId: '1', enterprises: [{ enterpriseId: '1', enterpriseName: '知序科技', role: 'ADMIN' }] };
const spacePage = { code: 0, data: { records: [{ id: '1', name: '差旅报销与费用制度', permissionVersion: 5 }], total: 1, pageNum: 1, pageSize: 10, pages: 1 } };
const pageOf = (records, total) => ({ code: 0, data: { records, total, pageNum: 1, pageSize: 20, pages: 1 } });

/** 登录态可切换：false 时 /auth/me 返回 401，模拟未登录访客。 */
let loggedIn = false;
/** 登录接口结果：ok 成功，denied 表示账号密码不对。 */
let loginResult = 'ok';
const requestLog = [];

const profile = await mkdtemp(join(tmpdir(), 'login-'));
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
    else if (path === '/api/auth/login') {
      if (loginResult === 'ok') { loggedIn = true; payload = { code: 0, data: account }; }
      else payload = { code: 401, message: '账号或密码不正确' };
    }
    else if (path === '/api/auth/csrf') payload = { code: 0, data: { headerName: 'X-CSRF-TOKEN', token: 'csrf-login-return' } };
    else if (/\/knowledge-spaces$/.test(path)) payload = spacePage;
    else if (/\/affairs\/confirmations$/.test(path)) payload = pageOf([], 0);
    else if (/\/affairs\/applications$/.test(path)) payload = pageOf([], 0);
    else if (/\/workspace\/chats$/.test(path)) payload = { code: 0, data: [] };
    else if (/answers\/mode$/.test(path)) payload = { code: 0, data: 'SYNTHETIC_MODEL' };
    else if (/answers\/retrieval-mode$/.test(path)) payload = { code: 0, data: 'HYBRID' };
    // 真实后端由 Spring Security 的失败处理器返回 HTTP 401。
    const responseCode = path === '/api/auth/login' && loginResult !== 'ok' ? 401 : 200;
    cdp('Fetch.fulfillRequest', {
      requestId: message.params.requestId, responseCode,
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
  const shot = async name => {
    await mkdir(outDir, { recursive: true });
    const { data } = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    await writeFile(join(outDir, `${prefix}-${name}.png`), Buffer.from(data, 'base64'));
    console.log(`saved ${prefix}-${name}.png`);
  };
  /** 当前页面标题（顶栏面包屑），用来判断到底停在哪一页。 */
  const currentPage = () => evaluate(`document.querySelector('.breadcrumb strong')?.textContent.trim() ?? ''`);
  const submitLogin = async () => {
    await wait('document.querySelector(".account-form fieldset") && !document.querySelector(".account-form fieldset").disabled', '登录表单可提交');
    await evaluate(`(() => {
      const fill = (selector, value) => {
        const node = document.querySelector(selector);
        node.value = value;
        node.dispatchEvent(new Event('input', { bubbles: true }));
      };
      fill('#enterprise-username', 'zhanglan');
      fill('#enterprise-password', 'Passw0rd!');
      document.querySelector('.account-form').requestSubmit();
      return true;
    })()`);
  };

  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base });
  await wait('document.querySelector(".overview-empty") !== null', '工作台未登录提示');

  // ① 工作台 → 登录 → 必须回到工作台。
  await click('用企业账号登录');
  await wait('document.querySelector(".account-form") !== null', '知识空间登录表单');
  assert.equal(await currentPage(), '知识空间', '点登录后应先到知识空间完成登录');
  const homeHint = await evaluate(`document.querySelector('.login-return')?.textContent.replace(/\\s+/g, ' ').trim() ?? ''`);
  assert.ok(homeHint.includes('工作台'), `登录页没有说明会回到工作台：${homeHint}`);
  await delay(400);
  await shot('01-login-from-home');

  await submitLogin();
  await wait(`document.querySelector('.work-overview') !== null`, '登录后回到工作台');
  assert.equal(await currentPage(), '工作台', '登录成功后没有回到来源页「工作台」');
  await wait('document.querySelectorAll(".overview-card").length === 3', '工作台概览读到数据');
  await delay(600);
  await shot('02-back-to-home');

  // ② 企业问答 → 登录 → 必须回到企业问答。
  loggedIn = false;
  await click('企业问答');
  await wait('document.querySelector(".answer-login-state") !== null', '企业问答未登录态');
  await click('登录并开始');
  await wait('document.querySelector(".account-form") !== null', '知识空间登录表单');
  const answerHint = await evaluate(`document.querySelector('.login-return')?.textContent.replace(/\\s+/g, ' ').trim() ?? ''`);
  assert.ok(answerHint.includes('企业问答'), `登录页没有说明会回到企业问答：${answerHint}`);
  await delay(300);
  await shot('03-login-from-answer');

  await submitLogin();
  await wait(`document.querySelector('.breadcrumb strong')?.textContent.trim() === '企业问答'`, '登录后回到企业问答');
  await wait('document.querySelector(".answer-login-state") === null', '企业问答已切到登录后状态');
  assert.equal(await currentPage(), '企业问答', '登录成功后没有回到来源页「企业问答」');
  await delay(600);
  await shot('04-back-to-answer');

  // ③ 本来就在知识空间登录，不该被弹走。
  loggedIn = false;
  await click('知识空间');
  await wait('document.querySelector(".account-form") !== null', '知识空间登录表单');
  assert.equal(await evaluate(`document.querySelector('.login-return') !== null`), false, '直接来知识空间登录时不应显示回流提示');
  await submitLogin();
  await wait('document.querySelector(".space-list") !== null', '登录后加载空间列表');
  assert.equal(await currentPage(), '知识空间', '在知识空间自己登录却被弹走了');
  await delay(400);
  await shot('05-stay-in-knowledge');

  // ④ 登录失败不许回跳：否则会被弹回来源页，反而看不到错误原因。
  loggedIn = false;
  loginResult = 'denied';
  await click('企业问答');
  await wait('document.querySelector(".answer-login-state") !== null', '企业问答未登录态');
  await click('登录并开始');
  await wait('document.querySelector(".account-form") !== null', '知识空间登录表单');
  await submitLogin();
  await wait('document.querySelector(".manager-feedback.failed") !== null', '登录失败提示');
  assert.equal(await currentPage(), '知识空间', '登录失败却回跳了，用户看不到失败原因');
  await delay(300);
  await shot('06-login-failed-stays');

  console.log('login-return-check 通过：工作台/企业问答登录后各自回流、原地登录不回跳、失败不回跳');
} finally {
  try { socket?.close(); } catch { /* 关闭失败不影响结论 */ }
  browser.kill();
}
