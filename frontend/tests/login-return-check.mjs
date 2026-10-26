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
 * ⑤ 企业问答跨页保留草稿、在途请求和结果，退出后隔离账号；空间详情从顶部进入。
 * 全程拦截接口，不访问真实后端。
 */
const base = process.env.AUDIT_BASE ?? 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outDir = process.env.UI_SCREENSHOT_DIR ?? './test-artifacts/audit';
const prefix = process.env.AUDIT_PREFIX ?? 'login';
const width = Number(process.env.AUDIT_WIDTH ?? 1440);
const height = Number(process.env.AUDIT_HEIGHT ?? 900);

const account = { userId: '1', enterprises: [{ enterpriseId: '1', enterpriseName: '知序科技', role: 'ADMIN' }] };
const spacePage = { code: 0, data: { records: Array.from({ length: 10 }, (_, index) => ({ id: String(index + 1), name: `校园办事指南 ${index + 1}`, permissionVersion: 5 })), total: 10, pageNum: 1, pageSize: 10, pages: 1 } };
const pageOf = (records, total) => ({ code: 0, data: { records, total, pageNum: 1, pageSize: 20, pages: 1 } });
const evidence = { chunkId: '101', documentId: '21', versionId: '35', versionNo: 1, title: '校园网账号指南', chunkIndex: 0,
  content: '忘记校园网密码可通过统一身份平台重置。', startOffset: 0, endOffset: 21, startLine: 1, endLine: 1 };
evidence.endOffset = evidence.content.length;
const answerFor = question => ({ code: 0, data: { mode: 'SYNTHETIC_MODEL', status: 'ANSWERED', message: '已根据校园指南回答。', elapsedMs: 100,
  evidence: [{ spaceId: '1', spaceName: '校园办事指南 1', evidence }],
  parts: [{ id: 1, question, status: 'ANSWERED', gaps: [], paragraphs: [{ text: '请通过统一身份平台重置校园网密码。',
    citations: [{ sourceId: 1, quote: evidence.content, evidence }] }] }] } });
const heldAnswers = [];

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
    else if (path === '/api/auth/logout') loggedIn = false;
    else if (/\/enterprises\/1\/answers$/.test(path)) {
      heldAnswers.push({ requestId: message.params.requestId, payload: answerFor(JSON.parse(message.params.request.postData).question) });
      return;
    }
    else if (/\/knowledge-spaces$/.test(path)) payload = spacePage;
    else if (/\/knowledge-spaces\/\d+$/.test(path)) payload = { code: 0, data: spacePage.data.records.find(space => path.endsWith('/' + space.id)) };
    else if (/\/documents$/.test(path)) payload = pageOf([], 0);
    else if (/\/grants$/.test(path)) payload = { code: 404, message: '资源不存在或不可访问' };
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

  /**
   * 登录态现在存在前端模块里，同一会话内切页不再重新询问后端。
   * 所以要模拟一个「未登录访客」，必须重新加载页面，让模块重新核验一次。
   */
  const reopenAsGuest = async () => {
    loggedIn = false;
    await cdp('Page.navigate', { url: base });
    await wait('document.querySelector(".overview-empty") !== null', '工作台未登录提示');
  };

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
  await reopenAsGuest();
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
  await reopenAsGuest();
  await click('知识空间');
  await wait('document.querySelector(".account-form") !== null', '知识空间登录表单');
  assert.equal(await evaluate(`document.querySelector('.login-return') !== null`), false, '直接来知识空间登录时不应显示回流提示');
  await submitLogin();
  await wait('document.querySelector(".space-list") !== null', '登录后加载空间列表');
  assert.equal(await currentPage(), '知识空间', '在知识空间自己登录却被弹走了');
  await delay(400);
  await shot('05-stay-in-knowledge');

  // ④ 登录失败不许回跳：否则会被弹回来源页，反而看不到错误原因。
  loginResult = 'denied';
  await reopenAsGuest();
  await click('企业问答');
  await wait('document.querySelector(".answer-login-state") !== null', '企业问答未登录态');
  await click('登录并开始');
  await wait('document.querySelector(".account-form") !== null', '知识空间登录表单');
  await submitLogin();
  await wait('document.querySelector(".manager-feedback.failed") !== null', '登录失败提示');
  assert.equal(await currentPage(), '知识空间', '登录失败却回跳了，用户看不到失败原因');
  await delay(300);
  await shot('06-login-failed-stays');

  // ⑤ 延迟回答，验证切页不会中止请求或重新 POST。
  loginResult = 'ok';
  await submitLogin();
  await wait(`document.querySelector('.breadcrumb strong')?.textContent.trim() === '企业问答'`, '恢复企业问答');
  const navigate = async label => {
    await evaluate(`document.querySelector('.mobile-menu')?.click()`);
    await evaluate(`([...document.querySelectorAll('.primary-nav .nav-item')].find(node => node.textContent.trim() === ${JSON.stringify(label)})).click()`);
    await wait(`document.querySelector('.breadcrumb strong')?.textContent.trim() === ${JSON.stringify(label)}`, label);
  };
  const fillQuestion = value => evaluate(`(() => { const input = document.querySelector('#knowledge-question'); input.value = ${JSON.stringify(value)}; input.dispatchEvent(new Event('input', { bubbles: true })); })()`);
  const submitQuestion = async value => {
    await wait(`document.querySelector('#knowledge-question')?.disabled === false`, '问答可提交');
    await fillQuestion(value);
    await evaluate(`document.querySelector('.enterprise-composer').requestSubmit()`);
  };
  const releaseAnswer = async (cancelled = false) => {
    const held = heldAnswers.shift();
    assert.ok(held, '没有收到待返回的问答请求');
    try {
      await cdp('Fetch.fulfillRequest', { requestId: held.requestId, responseCode: 200,
        responseHeaders: [{ name: 'Content-Type', value: 'application/json' }], body: Buffer.from(JSON.stringify(held.payload)).toString('base64') });
    } catch (error) { if (!cancelled) throw error; }
  };
  const waitForAnswerRequest = async () => {
    for (let attempt = 0; attempt < 240 && !heldAnswers.length; attempt++) await delay(50);
    assert.ok(heldAnswers.length, '等待问答请求超时');
  };
  await submitQuestion('校园网密码忘了怎么重置？');
  await wait('document.querySelectorAll(".answer-progress").length === 1', '问答等待中');
  await waitForAnswerRequest();
  await fillQuestion('还没发送的校园事务问题');
  await navigate('工作台');
  await navigate('知识空间');
  await navigate('联网问答体验');
  await navigate('企业问答');
  assert.equal(await evaluate('document.querySelectorAll(".answer-card").length'), 1, '切页丢失问题');
  assert.equal(await evaluate('document.querySelectorAll(".answer-progress").length'), 1, '切页丢失在途请求');
  assert.equal(await evaluate('document.querySelector("#knowledge-question").value'), '还没发送的校园事务问题', '切页丢失草稿');
  await navigate('知识空间');
  await releaseAnswer();
  await navigate('企业问答');
  await wait('document.querySelector(".answer-text")?.textContent.includes("统一身份平台")', '切页期间收到的回答');
  assert.equal(requestLog.filter(path => path === '/api/enterprises/1/answers').length, 1, '切页重复提交问答');
  await wait('document.querySelector(".composer-help")?.textContent.includes("Enter")', '完成后释放并发槽位');
  await shot('07-answer-retained');

  // ⑥ 在途退出后换账号，不显示旧问题、草稿或迟到的回答。
  await submitQuestion('账号一尚未返回的问题');
  await waitForAnswerRequest();
  await navigate('知识空间');
  await wait('document.querySelector(".account-line button")?.disabled === false', '可退出账号');
  await click('退出登录');
  await wait('document.querySelector(".account-form") !== null', '已退出');
  await navigate('企业问答');
  await wait('document.querySelector(".answer-login-state") !== null', '问答同步退出');
  assert.equal(await evaluate('document.querySelectorAll(".answer-card").length'), 0, '退出后残留旧消息');
  await click('登录并开始');
  account.userId = '2';
  await submitLogin();
  await wait('document.querySelector("#knowledge-question")?.disabled === false', '新账号就绪');
  await releaseAnswer(true);
  await delay(1100);
  assert.equal(await evaluate('document.querySelectorAll(".answer-card").length'), 0, '新账号收到旧账号回答');
  assert.equal(await evaluate('document.querySelector("#knowledge-question").value'), '', '新账号看到旧草稿');

  // ⑦ 实际滚动容器有滚动量，打开目录底部的空间后详情必须从顶部展示。
  await navigate('知识空间');
  await wait('document.querySelectorAll(".space-cards button:not(:disabled)").length === 10', '空间目录加载');
  const beforeScroll = await evaluate(`(() => { const view = document.querySelector('.enterprise-workspace'); view.scrollTop = view.scrollHeight; return view.scrollTop; })()`);
  assert.ok(beforeScroll > 100, '目录不足以复现滚动问题');
  await evaluate(`document.querySelectorAll('.space-cards button')[9].click()`);
  await wait('document.querySelector(".space-result h3")?.textContent === "校园办事指南 10"', '空间详情加载');
  await wait('document.querySelector(".catalog-heading") !== null', '资料目录加载');
  assert.equal(await evaluate('document.querySelector(".enterprise-workspace").scrollTop'), 0, '空间详情沿用目录底部滚动位置');
  await shot('08-space-top');
  await navigate('工作台');
  await evaluate(`const view = document.querySelector('.enterprise-workspace'); view.scrollTop = view.scrollHeight;`);
  await navigate('知识空间');
  assert.equal(await evaluate('document.querySelector(".enterprise-workspace").scrollTop'), 0, '从其他页面进入知识空间没有归顶');

  console.log('login-return-check 通过：4组登录回流、问答跨页保留且不重复提交、在途账号隔离、空间详情和页面滚动归顶');
} finally {
  try { socket?.close(); } catch { /* 关闭失败不影响结论 */ }
  browser.kill();
}
