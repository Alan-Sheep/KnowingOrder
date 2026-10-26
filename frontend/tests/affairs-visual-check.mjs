import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

// 仅用于人工核对「员工事务 → 补贴申请」表单的渲染与分摊提示，不调用真实后端或第三方服务。
const base = 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outDir = process.env.UI_SCREENSHOT_DIR ?? './test-artifacts';

const me = { code: 0, data: { userId: '1', enterprises: [{ enterpriseId: '1', enterpriseName: '知序大学', role: 'ADMIN' }] } };
const rules = { code: 0, data: { records: [{ id: '1', name: '月度餐饮补贴', version: 2, status: 'ACTIVE',
  period: 'MONTH', memberLimitCents: '50000', applicationLimitCents: '10000', requiresManager: true,
  effectiveFrom: '2026-10-01 00:00:00', effectiveTo: null }], total: 1, pageNum: 1, pageSize: 20, pages: 1 } };
const members = { code: 0, data: [{ memberId: '1', username: '张伟（我）' }, { memberId: '2', username: '李娜' }, { memberId: '3', username: '王强' }] };
const emptyPage = { code: 0, data: { records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0 } };

await fetch(base).catch(() => { throw new Error('请先运行 npm.cmd run dev。'); });
const profile = await mkdtemp(join(tmpdir(), 'ragqa-affairs-'));
const browser = spawn(browserPath, ['--headless=new', '--disable-gpu', '--no-first-run',
  '--no-default-browser-check', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'],
{ stdio: 'ignore', windowsHide: true });
let socket;
try {
  let port;
  for (let attempt = 0; attempt < 150; attempt++) {
    try { port = Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); break; }
    catch { await delay(100); }
  }
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(pages.find(item => item.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  let nextId = 0;
  const pending = new Map();
  const exceptions = [];
  const cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId;
    const timer = setTimeout(() => reject(new Error(`超时：${method}`)), 10000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params }));
  });
  socket.addEventListener('message', event => {
    const value = JSON.parse(event.data);
    if (value.id) {
      const item = pending.get(value.id);
      if (!item) return;
      clearTimeout(item.timer); pending.delete(value.id);
      if (value.error) item.reject(new Error(value.error.message)); else item.resolve(value.result);
    } else if (value.method === 'Runtime.exceptionThrown') exceptions.push(value.params.exceptionDetails.text);
    else if (value.method === 'Fetch.requestPaused') {
      const path = new URL(value.params.request.url).pathname;
      const payload = path === '/api/auth/me' ? me
        : path.endsWith('/affairs/rules') ? rules
          : path.endsWith('/affairs/members') ? members : emptyPage;
      cdp('Fetch.fulfillRequest', { requestId: value.params.requestId, responseCode: 200,
        responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
        body: Buffer.from(JSON.stringify(payload)).toString('base64') }).catch(() => {});
    }
  });
  const evaluate = async expression => {
    const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? '执行失败');
    return result.result.value;
  };
  const wait = async (expression, label = expression) => {
    for (let index = 0; index < 120; index++) { if (await evaluate(expression)) return; await delay(50); }
    throw new Error(`等待超时：${label}`);
  };
  const click = text => evaluate(`([...document.querySelectorAll('button')].find(node => node.textContent.includes(${JSON.stringify(text)})))?.click()`);
  const shot = async (name, full = false) => {
    await mkdir(outDir, { recursive: true });
    const params = { format: 'png', captureBeyondViewport: full };
    if (full) {
      const metrics = await cdp('Page.getLayoutMetrics');
      const size = metrics.cssContentSize ?? metrics.contentSize;
      params.clip = { x: 0, y: 0, width: size.width, height: Math.min(size.height, 4000), scale: 1 };
    }
    const { data } = await cdp('Page.captureScreenshot', params);
    await writeFile(join(outDir, name), Buffer.from(data, 'base64'));
  };
  const setValue = (selector, value) => evaluate(`(() => {
    const node = document.querySelector(${JSON.stringify(selector)});
    if (!node) return false;
    node.value = ${JSON.stringify(value)};
    node.dispatchEvent(new Event('input', { bubbles: true }));
    node.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`);

  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*', requestStage: 'Request' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1180, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base });
  await wait('document.querySelector(".enterprise-workspace[data-page=home]") !== null');

  await click('员工事务');
  await wait('document.querySelector(".affair-compose") !== null', '补贴申请表单未渲染');
  await shot('affairs-compose-empty.png');

  // 选规则 + 选平台 + 填金额 30 元 + 本人分摊 30 元
  await evaluate(`(() => {
    const selects = document.querySelectorAll('.affair-compose select');
    selects[0].value = '1'; selects[0].dispatchEvent(new Event('change', { bubbles: true }));
    selects[1].value = 'meituan'; selects[1].dispatchEvent(new Event('change', { bubbles: true }));
    selects[2].value = '1'; selects[2].dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  await setValue('.affair-compose .field-amount input', '30');
  await setValue('.share-row .amount-input input', '30');
  await wait('document.querySelector(".share-summary.matched") !== null', '分摊合计未显示一致');
  const matched = await evaluate('document.querySelector(".share-summary").textContent.trim()');
  assert.ok(matched.includes('一致'), `分摊一致提示不正确：${matched}`);
  await shot('affairs-compose-filled.png', true);
  // 表单较长，滚动内层容器看金额与分摊部分。
  await evaluate('document.querySelector(".enterprise-workspace").scrollTop = 99999');
  await delay(300);
  await shot('affairs-compose-lower.png');

  // 把分摊改成 20 元，应当提示差 10 元
  await setValue('.share-row .amount-input input', '20');
  await wait('document.querySelector(".share-summary.mismatch") !== null', '分摊差额未提示');
  const mismatch = await evaluate('document.querySelector(".share-summary").textContent.trim()');
  assert.ok(mismatch.includes('10.00'), `差额提示不正确：${mismatch}`);

  const body = await evaluate('document.querySelector(".affair-compose").textContent');
  for (const forbidden of ['（分）', 'taobao', 'yyyy-MM-dd']) {
    assert.ok(!body.includes(forbidden), `表单仍出现新手难懂的内容：${forbidden}`);
  }
  assert.equal(exceptions.length, 0, `存在脚本异常：${exceptions.join('; ')}`);
  console.log('补贴申请表单渲染检查通过：分摊一致/差额提示正常，无“分”“taobao”“yyyy-MM-dd”等内部表述。');
  console.log(`截图：${outDir}/affairs-compose-empty.png、affairs-compose-filled.png`);
} finally {
  try { socket?.close(); } catch { /* 关闭失败不影响结果 */ }
  browser.kill();
}
