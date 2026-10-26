import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { safeUrl, sendQuestion, validateAnswer } from '../src/api.ts';

// 使用本机浏览器，不新增测试依赖；所有问答请求由浏览器拦截器提供固定数据。
const base = 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const screenshotDir = process.env.UI_SCREENSHOT_DIR;
const fixture = (question = '测试问题') => ({
  requestId: 'ui-test-request', status: 'COMPLETED', message: '完成', elapsedMillis: 120,
  answers: [{ id: 1, question, status: 'ANSWERED', basis: 'GENERAL',
    answer: `对应回答：${question}`, message: null, sources: [] }]
});
let checks = 0;
function passed(name) { checks++; console.log(`通过 ${checks}：${name}`); }

assert.equal(validateAnswer(fixture()), true);
for (const invalid of [null, {}, { ...fixture(), answers: [] }, { ...fixture(), elapsedMillis: -1 },
  { ...fixture(), answers: [fixture().answers[0], fixture().answers[0]] },
  { ...fixture(), answers: [{ ...fixture().answers[0], status: 'MADE_UP' }] }]) assert.equal(validateAnswer(invalid), false);
passed('拒绝空值、重复子问题编号、未知状态及不完整响应');
for (const url of ['javascript:alert(1)', 'data:text/html,hello', 'file:///C:/test', 'https://user:secret@example.com', '无效网址']) assert.equal(safeUrl(url), undefined);
assert.equal(safeUrl('https://example.com/source'), 'https://example.com/source');
passed('危险协议和带凭据链接不可打开');

// 单独验证超时和取消，不需要真的等待 95 秒。
globalThis.window = { setTimeout: globalThis.setTimeout, clearTimeout: globalThis.clearTimeout };
const originalFetch = globalThis.fetch;
globalThis.fetch = (_url, { signal }) => new Promise((_resolve, reject) => {
  if (signal.aborted) reject(new DOMException('取消', 'AbortError'));
  else signal.addEventListener('abort', () => reject(new DOMException('取消', 'AbortError')), { once: true });
});
await assert.rejects(sendQuestion('超时测试', undefined, 10), /停止本次连接/);
const aborted = new AbortController();
aborted.abort();
await assert.rejects(sendQuestion('取消测试', aborted.signal), /不能保证后台任务立即取消/);
globalThis.fetch = originalFetch;
delete globalThis.window;
passed('等待有上限，取消只承诺断开连接，不重复提交');

await fetch(base).catch(() => { throw new Error('请先在另一个终端运行 npm.cmd run dev。'); });
const profile = await mkdtemp(join(tmpdir(), 'ragqa-ui-browser-'));
const browser = spawn(browserPath, ['--headless=new', '--disable-gpu', '--no-first-run',
  '--no-default-browser-check', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'],
{ stdio: 'ignore', windowsHide: true });
let launchError;
browser.on('error', error => { launchError = error; });
let socket;
let cdp;

try {
  let debuggingPort;
  for (let attempt = 0; attempt < 150; attempt++) {
    if (launchError) throw launchError;
    try { debuggingPort = Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); break; }
    catch { await delay(100); }
  }
  assert.ok(debuggingPort, '浏览器未启动调试端口');
  const pages = await (await fetch(`http://127.0.0.1:${debuggingPort}/json/list`)).json();
  const page = pages.find(item => item.type === 'page');
  assert.ok(page, '浏览器没有可用页面');
  socket = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  let nextId = 0;
  const pending = new Map();
  const events = new Map();
  const exceptions = [];
  cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`浏览器命令超时：${method}`)); }, 10000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params }));
  });
  socket.addEventListener('message', event => {
    const value = JSON.parse(event.data);
    if (value.id) {
      const item = pending.get(value.id);
      if (!item) return;
      clearTimeout(item.timer);
      pending.delete(value.id);
      if (value.error) item.reject(new Error(value.error.message)); else item.resolve(value.result);
    } else events.get(value.method)?.(value.params);
  });
  const evaluate = async expression => {
    const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? '页面表达式执行失败');
    return result.result.value;
  };
  const wait = async (expression, message = expression) => {
    for (let index = 0; index < 100; index++) { if (await evaluate(expression)) return; await delay(50); }
    throw new Error(`页面断言超时：${message}`);
  };
  const click = text => evaluate(`([...document.querySelectorAll('button')].find(node => node.textContent.includes(${JSON.stringify(text)})))?.click()`);
  const fill = text => evaluate(`(() => { const node = document.querySelector('#question'); node.value = ${JSON.stringify(text)}; node.dispatchEvent(new Event('input', {bubbles: true})); })()`);
  const submit = async text => { await fill(text); await evaluate('document.querySelector(".composer").requestSubmit()'); };
  const screenshot = async name => {
    if (!screenshotDir) return;
    await mkdir(screenshotDir, { recursive: true });
    const { data } = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    await writeFile(join(screenshotDir, name), Buffer.from(data, 'base64'));
  };
  let mode = 'success';
  let requestCount = 0;
  let productRequestCount = 0;
  let productMode = 'success';
  const held = [];
  const fulfil = (request, status, payload) => cdp('Fetch.fulfillRequest', {
    requestId: request.requestId, responseCode: status,
    responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
    body: Buffer.from(JSON.stringify(payload)).toString('base64')
  });
  const mockErrors = [];
  events.set('Runtime.exceptionThrown', event => exceptions.push(event.exceptionDetails.text));
  events.set('Fetch.requestPaused', event => {
    if (new URL(event.request.url).pathname === '/api/auth/me') {
      fulfil(event, 401, { code: 401, data: null }).catch(error => mockErrors.push(error.message));
      return;
    }
    if (new URL(event.request.url).pathname === '/api/products/search') {
      productRequestCount++;
      const keyword = JSON.parse(event.request.postData).keyword;
      const item = { id: 'mock-coffee', title: '咖啡（示例）', merchant: '虚构门店', referencePrice: 12,
        currency: 'CNY', linkType: 'PLATFORM_HOME', jumpUrl: 'https://www.taobao.com/' };
      const data = { provider: 'MOCK', simulated: true, notice: '模拟商品，不对应真实商品。',
        items: keyword === '不存在商品' ? [] : [item] };
      if (productMode === 'invalid') data.items = [{ ...item, jumpUrl: 'javascript:alert(1)' }];
      fulfil(event, productMode === 'unavailable' ? 503 : 200, { code: 0, data })
        .catch(error => mockErrors.push(error.message));
      return;
    }
    requestCount++;
    if (mode === 'hold') { held.push(event); return; }
    const question = JSON.parse(event.request.postData).question;
    const status = mode === 'unavailable' ? 503 : mode === 'rate' ? 429 : mode === 'permission' ? 403 : mode === 'missing' ? 404 : 200;
    let data = { code: 0, data: fixture(question) };
    if (mode === 'empty') data = { code: 0, data: null };
    if (mode === 'business') data = { code: 50001, message: '内部敏感信息不应直接展示' };
    fulfil(event, status, data).catch(error => mockErrors.push(error.message));
  });
  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/chat*', requestStage: 'Request' },
    { urlPattern: '*/api/auth/me', requestStage: 'Request' },
    { urlPattern: '*/api/products/search*', requestStage: 'Request' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base });
  await wait('document.querySelector(".enterprise-workspace[data-page=home]") !== null');
  assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
  assert.equal(await evaluate('document.querySelector(".send-button").disabled'), true);
  assert.ok((await evaluate('document.querySelector(".work-hero").textContent')).includes('读懂公司知识'));
  assert.equal(await evaluate('document.title'), '知序 · 企业知识与工作助手');
  assert.equal(await evaluate('document.querySelector(".primary-nav [aria-current=page]").textContent.trim()'), '工作台');
  await screenshot('frontend-desktop.png');
  await evaluate('document.querySelector(".header-actions button").click()');
  await wait('document.querySelector("dialog").open');
  assert.ok((await evaluate('document.querySelector("dialog").textContent')).includes('企业知识是主线'));
  await click('知道了');
  passed('默认企业工作台突出知识主线，导航状态正确，无虚构企业数据或水平溢出');

  assert.equal(await evaluate('document.querySelector(".new-chat") === null && document.querySelector(".history-list") === null'), true);
  await click('企业问答');
  await wait('document.querySelector(".enterprise-workspace[data-page=enterprise]") !== null');
  await wait('document.querySelector(".knowledge-answer") !== null');
  assert.equal(requestCount, 0);
  assert.equal(await evaluate('document.querySelector(".composer").getBoundingClientRect().height'), 0);
  assert.ok((await evaluate('document.querySelector(".knowledge-answer").textContent')).includes('登录企业账号'));
  assert.equal(await evaluate('document.querySelector("input#answer-enterprise") === null'), true);
  await screenshot('frontend-enterprise.png');
  await click('知识空间');
  await wait('document.querySelector(".enterprise-workspace[data-page=knowledge]") !== null');
  await wait('document.getElementById("enterprise-username") !== null');
  assert.equal(await evaluate('document.querySelectorAll("input[type=file]").length'), 0);
  assert.ok((await evaluate('document.querySelector(".knowledge-manager").textContent')).includes('请使用管理员开通的企业账号登录'));
  assert.equal(requestCount, 0);
  await screenshot('frontend-knowledge.png');
  passed('企业问答示例不发送内部问题，知识空间提供真实登录入口，不伪造文档或上传能力');

  await click('员工事务');
  await wait('document.querySelector(".enterprise-workspace[data-page=affairs]") !== null');
  // 补贴与财务已经接入，页面改为区分「Mock 可体验」和「已接入」，并在底部说明办理边界。
  const affairsText = await evaluate('document.querySelector(".enterprise-workspace[data-page=affairs]").textContent');
  assert.ok(affairsText.includes('Mock'));
  assert.ok(affairsText.includes('已接入'));
  assert.ok(affairsText.includes('不创建订单'));
  assert.equal(requestCount, 0);
  assert.equal(productRequestCount, 0);
  await screenshot('frontend-affairs.png');
  passed('员工事务区分Mock体验与已接入补贴财务，底部说明办理边界，不产生业务记录');

  await click('商品查询');
  await wait('document.querySelector(".product-search") !== null');
  await evaluate('document.querySelector(".product-form").requestSubmit()');
  await wait('document.querySelector(".product-list a") !== null');
  assert.ok((await evaluate('document.querySelector(".product-search").textContent')).includes('不对应真实商品'));
  assert.deepEqual(await evaluate('(() => { const a = document.querySelector(".product-list a"); return [a.href, a.target, a.rel]; })()'),
    ['https://www.taobao.com/', '_blank', 'noopener noreferrer']);
  // 拦住真实外部导航，只验证用户点击的目标；测试不访问淘宝或产生交易。
  await evaluate('document.querySelector(".product-list a").addEventListener("click", event => { event.preventDefault(); window.__productDestination = event.currentTarget.href; })');
  await evaluate('document.querySelector(".product-list a").click()');
  assert.equal(await evaluate('window.__productDestination'), 'https://www.taobao.com/');
  assert.equal(productRequestCount, 1);
  for (const [scenario, keyword, expected] of [['success', '不存在商品', '没有匹配'],
    ['invalid', '咖啡', '契约'], ['unavailable', '咖啡', '暂不可用']]) {
    productMode = scenario;
    await evaluate(`(() => { const node = document.getElementById('product-keyword'); node.value = ${JSON.stringify(keyword)}; node.dispatchEvent(new Event('input', {bubbles: true})); })()`);
    await evaluate('document.querySelector(".product-form").requestSubmit()');
    await wait(`document.querySelector('.product-search').textContent.includes(${JSON.stringify(expected)})`);
    assert.equal(await evaluate('document.querySelectorAll(".product-list a").length'), 0);
  }
  assert.equal(productRequestCount, 4);
  await click('知道了');
  passed('模拟商品查询、明确标识、安全跳转、空结果和失败终态均正常，不自动重试');

  await click('联网问答体验');
  await wait('document.querySelector(".public-scope-notice")?.getBoundingClientRect().height > 0');
  assert.ok((await evaluate('document.querySelector(".public-scope-notice").textContent')).includes('请勿输入内部资料'));
  await click('体验回答示例');
  await wait('document.querySelectorAll(".answer-part").length === 3');
  assert.equal(requestCount, 0);
  assert.equal(await evaluate('document.querySelectorAll(".evidence-gap").length'), 1);
  await evaluate('document.querySelector(".citation").click()');
  await wait('document.querySelector(".source-card.highlighted") !== null');
  await screenshot('frontend-answer.png');
  passed('三问题示例、资料不足和引用定位，不产生真实请求');

  await click('开启新对话');
  await fill('😀'.repeat(501));
  assert.equal(await evaluate('document.querySelector(".send-button").disabled'), true);
  await fill('😀'.repeat(500));
  assert.equal(await evaluate('document.querySelector(".send-button").disabled'), false);
  await evaluate('document.querySelector("#question").dispatchEvent(new KeyboardEvent("keydown", {key: "Enter", isComposing: true, bubbles: true}))');
  assert.equal(requestCount, 0);
  await evaluate('document.querySelector("#question").dispatchEvent(new KeyboardEvent("keydown", {key: "Enter", shiftKey: true, bubbles: true}))');
  assert.equal(requestCount, 0);
  passed('500 个 Unicode 字符边界、中文输入法和换行不会误提交');

  mode = 'hold';
  await submit('第一个独立问题');
  await wait('document.querySelectorAll(".pending-answer").length === 1');
  await fill('第二个独立问题');
  assert.equal(await evaluate('document.querySelector(".send-button").disabled'), false);
  await evaluate('document.querySelector(".composer").requestSubmit()');
  await wait('document.querySelectorAll(".pending-answer").length === 2');
  await fill('第三个待发送问题');
  assert.equal(await evaluate('document.querySelector(".send-button").disabled'), true);
  assert.equal(held.length, 2);
  await click('工作台');
  await wait('document.querySelector(".enterprise-workspace[data-page=home]") !== null');
  assert.equal(await evaluate('document.querySelector(".send-button").disabled'), true);
  assert.equal(held.length, 2);
  await click('联网问答体验');
  await wait('document.querySelector(".pending-answer")?.getBoundingClientRect().height > 0');
  // 故意逆序完成，验证不会把后到结果写到其他问题下。
  await fulfil(held[1], 200, { code: 0, data: fixture('第二个独立问题') });
  await fulfil(held[0], 200, { code: 0, data: fixture('第一个独立问题') });
  await wait('document.querySelectorAll(".pending-answer").length === 0');
  const results = await evaluate('[...document.querySelectorAll(".turn")].map(node => ({q: node.querySelector(".user-bubble").textContent, a: node.querySelector(".answer-text").textContent}))');
  assert.ok(results.every(result => result.a.includes(result.q)));
  passed('可并发提交两次，第三次受限，逆序完成仍正确对应原问题');

  for (const [scenario, expected] of [['unavailable', '服务暂时不可用'], ['rate', '当前请求较多'],
    ['permission', '未获授权'], ['missing', '未找到问答接口'], ['empty', '数据不完整'], ['business', '这次请求未能完成']]) {
    mode = scenario;
    await click('开启新对话');
    const before = requestCount;
    await submit(`异常场景 ${scenario}`);
    await wait('document.querySelector(".error-answer") !== null');
    assert.ok((await evaluate('document.querySelector(".error-answer").textContent')).includes(expected));
    assert.equal(await evaluate('document.querySelectorAll(".pending-answer").length'), 0);
    assert.equal(requestCount, before + 1);
    assert.equal(await evaluate('document.body.textContent.includes("内部敏感信息不应直接展示")'), false);
  }
  passed('503、429、403、404、空数据和业务失败均给出反馈，不重发、不暴露原始异常');

  mode = 'success';
  await click('开启新对话');
  await submit('<img src=x onerror="window.__unsafe=true">');
  await wait('document.querySelector(".answer-text") !== null');
  assert.equal(await evaluate('document.querySelectorAll(".turn img").length'), 0);
  assert.equal(await evaluate('window.__unsafe === undefined'), true);
  passed('用户输入与模型内容按文字显示，不执行 HTML');

  await cdp('Network.enable');
  await cdp('Network.emulateNetworkConditions', { offline: true, latency: 0, downloadThroughput: 0, uploadThroughput: 0 });
  await wait('document.querySelector(".offline-banner") !== null');
  await fill('断网时不要重复提交');
  assert.equal(await evaluate('document.querySelector(".send-button").disabled'), true);
  await cdp('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
  await wait('document.querySelector(".offline-banner") === null');
  passed('断网时有可见提示并阻止提交，恢复后不会自动重发');

  await evaluate('document.querySelector(".context-heading .icon-button")?.click()');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await wait('innerWidth === 390');
  await click('开启新对话');
  await wait('document.querySelector("h1") !== null');
  await evaluate('document.querySelector(".toast-notification button")?.click()');
  await delay(300);
  assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
  await screenshot('frontend-mobile.png');
  await evaluate('document.querySelector(".mobile-menu").click()');
  await wait('document.querySelector(".workspace.sidebar-visible") !== null');
  await click('工作台');
  await wait('document.querySelector(".enterprise-workspace[data-page=home]") !== null && !document.querySelector(".workspace.sidebar-visible")');
  await screenshot('frontend-workspace-mobile.png');
  for (const width of [320, 390, 768, 1024, 1440]) {
    await cdp('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: width < 760 });
    await wait(`innerWidth === ${width}`);
    assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true, `${width}px页面溢出`);
    assert.equal(await evaluate('document.querySelector(".enterprise-workspace").scrollWidth <= document.querySelector(".enterprise-workspace").clientWidth'), true, `${width}px工作台溢出`);
  }
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await evaluate('document.querySelector(".mobile-menu").click()');
  await click('员工事务');
  await wait('document.querySelector(".enterprise-workspace[data-page=affairs]") !== null');
  assert.equal(await evaluate('document.querySelector(".enterprise-workspace").scrollWidth <= document.querySelector(".enterprise-workspace").clientWidth'), true);
  await screenshot('frontend-affairs-mobile.png');
  await evaluate('document.querySelector(".mobile-menu").click()');
  await click('联网问答体验');
  await wait('document.querySelector(".composer").getBoundingClientRect().height > 0');
  await evaluate('document.querySelector(".mobile-menu").click()');
  await evaluate('document.querySelector(".mobile-scrim").click()');
  await click('企业知识问答');
  await wait('document.querySelector(".enterprise-workspace[data-page=enterprise]") !== null');
  await wait('document.querySelector(".knowledge-answer") !== null');
  assert.ok((await evaluate('document.querySelector(".knowledge-answer").textContent')).includes('登录企业账号'));
  // 回到联网问答页，后面的提交与偏好快照都依赖这个页面处于激活状态。
  await evaluate('document.querySelector(".mobile-menu").click()');
  await click('联网问答体验');
  await wait('document.querySelector(".composer").getBoundingClientRect().height > 0');
  passed('手机无水平溢出，导航正常；企业知识入口直接进入问答页，不再停在「待接入」占位');

  const select = (id, value) => evaluate(`(() => { const node = document.getElementById(${JSON.stringify(id)}); node.value = ${JSON.stringify(value)}; node.dispatchEvent(new Event('change', {bubbles: true})); })()`);
  await evaluate('document.querySelector(".profile button").click()');
  await wait('document.getElementById("ui-language") !== null');
  for (const [locale, label] of [['en', 'Workspace settings'], ['fr', 'Paramètres'], ['ja', 'ワークスペース設定'],
    ['de', 'Einstellungen'], ['es', 'Configuración'], ['zh-CN', '工作空间设置']]) {
    await select('ui-language', locale);
    await wait(`document.documentElement.lang === ${JSON.stringify(locale)}`);
    assert.equal(await evaluate('document.querySelector(".preference-settings h2").textContent'), label);
    assert.equal(await evaluate('document.querySelector("dialog").scrollWidth <= document.querySelector("dialog").clientWidth'), true);
  }
  await select('ui-language', 'en');
  await select('answer-language', 'fr');
  await select('time-zone', 'America/New_York');
  assert.deepEqual(await evaluate('Object.keys(JSON.parse(localStorage.getItem("ragqa.preferences.v1"))).sort()'),
    ['answerLanguage', 'timeZone', 'uiLanguage', 'version']);
  await screenshot('frontend-settings-mobile.png');
  await evaluate('document.querySelector("dialog").close()');
  passed('六种界面语言实时切换，设置弹窗在手机上不溢出，仅保存四个偏好字段');

  mode = 'hold';
  const originalHeldCount = held.length;
  await submit('设置快照一');
  await wait('document.querySelectorAll(".pending-answer").length === 1');
  const firstSettings = JSON.parse(held[originalHeldCount].request.postData);
  assert.equal(firstSettings.timeZone, 'America/New_York');
  assert.equal(firstSettings.answerLanguage, 'fr');
  assert.ok(Number.isFinite(Date.parse(firstSettings.clientSubmittedAt)));
  await evaluate('document.querySelector(".profile button").click()');
  await select('answer-language', 'follow-ui');
  await select('time-zone', 'Asia/Tokyo');
  await evaluate('document.querySelector("dialog").close()');
  await submit('设置快照二');
  await wait('document.querySelectorAll(".pending-answer").length === 2');
  const secondSettings = JSON.parse(held[originalHeldCount + 1].request.postData);
  assert.equal(secondSettings.timeZone, 'Asia/Tokyo');
  assert.equal(secondSettings.answerLanguage, 'en');
  assert.deepEqual(JSON.parse(held[originalHeldCount].request.postData), firstSettings);
  await fulfil(held[originalHeldCount + 1], 200, { code: 0, data: fixture('设置快照二') });
  await fulfil(held[originalHeldCount], 200, { code: 0, data: fixture('设置快照一') });
  await wait('document.querySelectorAll(".pending-answer").length === 0');
  const displayedTime = await evaluate('[...document.querySelectorAll(".turn time")].at(-1).textContent');
  assert.equal(displayedTime, new Intl.DateTimeFormat('en', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: 'Asia/Tokyo' }).format(Date.parse(secondSettings.clientSubmittedAt)));
  passed('请求携带独立语言、时区、提交时间快照；中途改设置不修改旧请求，消息按选定时区显示');

  await cdp('Page.reload', { ignoreCache: true });
  await wait('document.documentElement.lang === "en" && document.querySelector("h1") !== null');
  await evaluate('document.querySelector(".profile button").click()');
  await wait('document.getElementById("time-zone")?.value === "Asia/Tokyo"');
  assert.equal(await evaluate('document.getElementById("answer-language").value'), 'follow-ui');
  const blockedStorage = await cdp('Page.addScriptToEvaluateOnNewDocument', { source: 'Object.defineProperty(window, "localStorage", { get() { throw new Error("模拟存储不可用"); } });' });
  await cdp('Page.reload', { ignoreCache: true });
  await wait('document.documentElement.lang === "zh-CN" && document.querySelector("h1") !== null');
  await evaluate('document.querySelector(".profile button").click()');
  await wait('document.getElementById("ui-language") !== null');
  await select('ui-language', 'en');
  await wait('document.documentElement.lang === "en"');
  assert.ok((await evaluate('document.querySelector(".preference-status").textContent')).includes('Storage unavailable'));
  await cdp('Page.removeScriptToEvaluateOnNewDocument', { identifier: blockedStorage.identifier });
  passed('偏好刷新后恢复；存储被禁止时安全回退，当前页面可用且明确显示保存失败');
  assert.deepEqual(exceptions, []);
  assert.deepEqual(mockErrors, []);
  passed('浏览器无未捕获的脚本异常');
  console.log(`前端检查完成：${checks}/${checks}。未调用真实模型或搜索服务。`);
} finally {
  if (cdp) await cdp('Browser.close').catch(() => {});
  socket?.close();
  if (!browser.killed) browser.kill();
}
