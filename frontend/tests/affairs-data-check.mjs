import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

/**
 * 员工事务数据接线回归测试。
 *
 * 这里的 mock 严格照抄后端真实响应的字段类型，不再用"理想格式"：
 * - id / memberId 为 JSON 字符串或数字（Long 输出字符串，long 输出数字）
 * - applicationLimitCents 为数字（后端 DTO 曾用原始 long，Jackson 不转字符串）
 * - requiresManager 为 1（tinyint）
 * 之所以这样写，是因为之前的测试用了全字符串金额，恰好绕过了真实断链点。
 */
const base = process.env.AUDIT_BASE ?? 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outDir = process.env.UI_SCREENSHOT_DIR ?? './test-artifacts/audit';
const prefix = process.env.AUDIT_PREFIX ?? 'affairs-data';

const me = { code: 0, data: { userId: '961', enterprises: [{ enterpriseId: '1', enterpriseName: '知序科技', role: 'ADMIN' }] } };
const rules = { code: 0, data: { records: [
  { id: '4', name: '差旅补贴（演示）', version: 1, status: 'ACTIVE', period: 'MONTH',
    memberLimitCents: '50000', applicationLimitCents: 20000, requiresManager: 1,
    effectiveFrom: '2026-10-24 17:01:24', effectiveTo: null }
], total: 1, pageNum: 1, pageSize: 20, pages: 1 } };
const members = { code: 0, data: [
  { memberId: 1, username: 'adminW' },
  { memberId: 812, username: 'chenhaoran' },
  { memberId: 813, username: 'chensiyuan' }
] };
const emptyPage = { code: 0, data: { records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0 } };
const spacePage = { code: 0, data: { records: [{ id: '1', name: '教学教务与学籍', permissionVersion: 3 }], total: 1, pageNum: 1, pageSize: 10, pages: 1 } };

await fetch(base).catch(() => { throw new Error('请先运行 npm.cmd run dev。'); });
const profile = await mkdtemp(join(tmpdir(), 'ragqa-affairs-'));
const browser = spawn(browserPath, ['--headless=new', '--disable-gpu', '--no-first-run',
  '--no-default-browser-check', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'],
{ stdio: 'ignore', windowsHide: true });
let socket;
const passed = label => console.log(`  PASS ${label}`);
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
  const asked = [];
  socket.addEventListener('message', event => {
    const value = JSON.parse(event.data);
    if (value.id) {
      const item = pending.get(value.id);
      if (!item) return;
      clearTimeout(item.timer); pending.delete(value.id);
      if (value.error) item.reject(new Error(value.error.message)); else item.resolve(value.result);
    } else if (value.method === 'Fetch.requestPaused') {
      const path = new URL(value.params.request.url).pathname;
      asked.push(path);
      let payload = emptyPage;
      if (path === '/api/auth/me') payload = me;
      else if (/^\/api\/enterprises\/\d+\/knowledge-spaces$/.test(path)) payload = spacePage;
      else if (path.endsWith('/affairs/rules')) payload = rules;
      else if (path.endsWith('/affairs/members')) payload = members;
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
    for (let index = 0; index < 200; index++) { if (await evaluate(expression)) return; await delay(50); }
    throw new Error(`等待超时：${label}`);
  };
  const click = text => evaluate(`([...document.querySelectorAll('button')].find(node => node.textContent.trim().includes(${JSON.stringify(text)})))?.click()`);
  const shot = async name => {
    await mkdir(outDir, { recursive: true });
    const { data } = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
    await writeFile(join(outDir, `${prefix}-${name}.png`), Buffer.from(data, 'base64'));
    console.log(`  saved ${prefix}-${name}.png`);
  };

  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base });
  await wait('document.querySelector(".workspace") !== null', '应用挂载');

  await click('员工事务');
  await wait('document.querySelector(".affairs-panel") !== null', '员工事务页');
  await delay(1200);

  const state = await evaluate(`(() => {
    const selects = [...document.querySelectorAll('.affairs-panel select')];
    const rule = selects[0];
    const member = selects[selects.length - 1];
    const options = node => node ? [...node.options].map(o => o.textContent.trim()) : [];
    return {
      ruleOptions: options(rule),
      memberOptions: options(member),
      feedback: document.querySelector('.panel-feedback')?.textContent?.trim() ?? '',
      failed: !!document.querySelector('.panel-feedback.failed')
    };
  })()`);

  if (state.failed) throw new Error(`页面报了失败提示：${state.feedback}`);
  if (!state.ruleOptions.some(text => text.includes('差旅补贴'))) {
    throw new Error(`补贴规则下拉没有数据，实际选项：${JSON.stringify(state.ruleOptions)}`);
  }
  passed(`补贴规则下拉已填充：${state.ruleOptions.filter(t => !t.startsWith('请选择')).join(' / ')}`);
  if (!state.memberOptions.some(text => text.includes('adminW'))) {
    throw new Error(`参与人下拉没有数据，实际选项：${JSON.stringify(state.memberOptions)}`);
  }
  passed(`参与人下拉已填充 ${state.memberOptions.length - 1} 人（首项为占位提示）`);
  if (!asked.some(path => path.endsWith('/affairs/members'))) {
    throw new Error('参与人接口没有被请求，说明 rules 失败后中断了后续加载');
  }
  passed('规则与参与人两个接口都真实发出');

  await shot('01-affairs-filled');
  console.log('DONE');
} finally {
  try { socket?.close(); } catch { /* noop */ }
  browser.kill();
}
