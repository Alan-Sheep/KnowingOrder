import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

// 仅用于人工核对登录态页面的视觉密度，不调用真实后端或第三方服务。
const base = process.env.AUDIT_BASE ?? 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outDir = process.env.UI_SCREENSHOT_DIR ?? './test-artifacts/audit';
const prefix = process.env.AUDIT_PREFIX ?? 'auth';

const me = { code: 0, data: { userId: '1', enterprises: [{ enterpriseId: '1', enterpriseName: '知序科技', role: 'ADMIN' }] } };
const spaceRecords = [
  { id: '1', name: '研发工作指南', permissionVersion: 3 },
  { id: '2', name: '人事制度与假期', permissionVersion: 2 },
  { id: '3', name: '财务报销流程', permissionVersion: 5 },
  { id: '4', name: '新员工入职手册', permissionVersion: 1 },
  { id: '5', name: '产品设计与体验规范', permissionVersion: 2 },
  { id: '6', name: '客户支持话术库', permissionVersion: 4 }
];
const spacePage = { code: 0, data: { records: spaceRecords, total: 6, pageNum: 1, pageSize: 10, pages: 1 } };
const oneSpace = { code: 0, data: { id: '1', name: '研发工作指南', permissionVersion: 3 } };
const rules = { code: 0, data: { records: [{ id: '1', name: '月度餐饮补贴', version: 2, status: 'ACTIVE',
  period: 'MONTH', memberLimitCents: '50000', applicationLimitCents: '10000', requiresManager: true,
  effectiveFrom: '2026-10-01 00:00:00', effectiveTo: null }], total: 1, pageNum: 1, pageSize: 20, pages: 1 } };
const members = { code: 0, data: [{ memberId: '1', username: '张伟' }, { memberId: '2', username: '李娜' }, { memberId: '3', username: '王强' }] };
const emptyPage = { code: 0, data: { records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0 } };

await fetch(base).catch(() => { throw new Error('请先运行 npm.cmd run dev。'); });
const profile = await mkdtemp(join(tmpdir(), 'ragqa-auth-'));
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
  const cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId;
    const timer = setTimeout(() => reject(new Error(`超时：${method}`)), 15000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params }));
  });
  let intercepted = 0;
  socket.addEventListener('message', event => {
    const value = JSON.parse(event.data);
    if (value.id) {
      const item = pending.get(value.id);
      if (!item) return;
      clearTimeout(item.timer); pending.delete(value.id);
      if (value.error) item.reject(new Error(value.error.message)); else item.resolve(value.result);
    } else if (value.method === 'Fetch.requestPaused') {
      const path = new URL(value.params.request.url).pathname;
      let payload = emptyPage;
      if (path === '/api/auth/me') payload = me;
      else if (/^\/api\/enterprises\/\d+\/knowledge-spaces$/.test(path)) payload = spacePage;
      else if (/^\/api\/enterprises\/\d+\/knowledge-spaces\/\d+$/.test(path)) payload = oneSpace;
      else if (path.endsWith('/affairs/rules')) payload = rules;
      else if (path.endsWith('/affairs/members')) payload = members;
      intercepted++;
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
    for (let index = 0; index < 160; index++) { if (await evaluate(expression)) return; await delay(50); }
    throw new Error(`等待超时：${label}`);
  };
  const click = text => evaluate(`([...document.querySelectorAll('button')].find(node => node.textContent.trim().includes(${JSON.stringify(text)})))?.click()`);
  const shot = async name => {
    await mkdir(outDir, { recursive: true });
    const { data } = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    await writeFile(join(outDir, `${prefix}-${name}.png`), Buffer.from(data, 'base64'));
    console.log(`saved ${prefix}-${name}.png`);
  };

  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base });
  await wait('document.querySelector(".workspace") !== null', '应用挂载');

  await click('知识空间');
  await wait('document.querySelector(".space-cards") !== null', '空间列表');
  await delay(900);
  await shot('01-space-list');

  await click('研发工作指南');
  await delay(1200);
  await shot('02-space-detail');

  await click('员工事务');
  await delay(1200);
  await shot('03-affairs');

  await click('企业问答');
  await delay(1200);
  await shot('04-enterprise');
  console.log('拦截请求数:', intercepted);
  console.log('DONE');
} finally {
  try { socket?.close(); } catch { /* noop */ }
  browser.kill();
}
