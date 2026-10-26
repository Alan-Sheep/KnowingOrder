import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

/*
 * 界面齐全度巡检（只读，不修改任何业务数据）：
 * 1. 以已登录管理员身份逐页渲染，记录未捕获异常与 console.error；
 * 2. 记录每个页面实际请求了哪些接口，找出「请求了但没人 mock」的路径，得到真实接口依赖面；
 * 3. 枚举每页的交互控件（button / input / select / details / a），标出 disabled 与绑定情况；
 * 4. 每页截图，供人工核对布局。
 * 全程用 CDP 拦截接口，不接触真实后端。
 */
const base = process.env.AUDIT_BASE ?? 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outDir = process.env.UI_SCREENSHOT_DIR ?? './test-artifacts/audit';
const prefix = process.env.AUDIT_PREFIX ?? 'scan';
const width = Number(process.env.AUDIT_WIDTH ?? 1440);
const height = Number(process.env.AUDIT_HEIGHT ?? 900);

const me = { code: 0, data: { userId: '1', enterprises: [
  { enterpriseId: '1', enterpriseName: '知序科技', role: 'ADMIN' }
] } };
const spacePage = { code: 0, data: { records: [
  { id: '1', name: '差旅报销与费用制度', permissionVersion: 5 },
  { id: '2', name: '人事制度与假期', permissionVersion: 2 }
], total: 2, pageNum: 1, pageSize: 20, pages: 1 } };

/** 已实现字段契约的接口返回真数据；其余返回 code 0 + data null，用于观察错误态。 */
function payloadFor(path) {
  if (path === '/api/auth/me') return me;
  if (path === '/api/auth/csrf') return { code: 0, data: { headerName: 'X-CSRF-TOKEN', token: 'csrf-scan' } };
  if (/^\/api\/enterprises\/\d+\/knowledge-spaces$/.test(path)) return spacePage;
  if (/^\/api\/enterprises\/\d+\/knowledge-spaces\/\d+$/.test(path))
    return { code: 0, data: { id: '1', name: '差旅报销与费用制度', permissionVersion: 5 } };
  if (/answers\/mode$/.test(path)) return { code: 0, data: 'SYNTHETIC_MODEL' };
  if (/answers\/retrieval-mode$/.test(path)) return { code: 0, data: 'HYBRID' };
  if (/\/workspace\/capabilities$/.test(path)) return { code: 0, data: { persistentTasks: true } };
  return { code: 0, data: null };
}

await fetch(base).catch(() => { throw new Error('请先运行 npm.cmd run dev。'); });
await mkdir(outDir, { recursive: true });
const profile = await mkdtemp(join(tmpdir(), 'ragqa-scan-'));
const browser = spawn(browserPath, ['--headless=new', '--disable-gpu', '--no-first-run',
  '--no-default-browser-check', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'],
{ stdio: 'ignore', windowsHide: true });
let socket;
try {
  let port;
  for (let i = 0; i < 200; i++) {
    try { port = Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); break; }
    catch { await delay(100); }
  }
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(pages.find(p => p.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });
  let nextId = 0;
  const pending = new Map();
  const cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++nextId;
    const timer = setTimeout(() => reject(new Error(`timeout ${method}`)), 20000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params }));
  });

  const requested = new Map();   // path -> 次数
  const failed = new Map();      // "status path" -> 次数
  const consoleErrors = [];
  let pageLabel = 'startup';

  socket.addEventListener('message', event => {
    const value = JSON.parse(event.data);
    if (value.id) {
      const item = pending.get(value.id);
      if (!item) return;
      clearTimeout(item.timer); pending.delete(value.id);
      value.error ? item.reject(new Error(value.error.message)) : item.resolve(value.result);
      return;
    }
    if (value.method === 'Fetch.requestPaused') {
      const path = new URL(value.params.request.url).pathname;
      requested.set(path, (requested.get(path) ?? 0) + 1);
      const body = Buffer.from(JSON.stringify(payloadFor(path))).toString('base64');
      cdp('Fetch.fulfillRequest', { requestId: value.params.requestId, responseCode: 200,
        responseHeaders: [{ name: 'Content-Type', value: 'application/json' }], body }).catch(() => {});
      return;
    }
    if (value.method === 'Runtime.exceptionThrown') {
      const d = value.params.exceptionDetails;
      consoleErrors.push(`[${pageLabel}] 未捕获异常: ${d.exception?.description ?? d.text}`);
      return;
    }
    if (value.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(value.params.type)) {
      const text = value.params.args.map(a => a.value ?? a.description ?? a.type).join(' ');
      if (text) consoleErrors.push(`[${pageLabel}] console.${value.params.type}: ${text.slice(0, 300)}`);
    }
  });

  const evaluate = async expression => {
    const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? 'eval failed');
    return result.result.value;
  };
  const shot = async name => {
    const result = await cdp('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(outDir, `${prefix}-${name}.png`), Buffer.from(result.data, 'base64'));
  };
  const waitFor = async (expression, label, tries = 200) => {
    for (let i = 0; i < tries; i++) {
      if (await evaluate(expression)) return;
      await delay(50);
    }
    console.log(`⚠ 等待超时：${label}`);
  };
  const clickText = async text => evaluate(`(() => {
    const node = [...document.querySelectorAll('button, a, summary')]
      .find(n => n.textContent.trim().includes(${JSON.stringify(text)}) && !n.disabled);
    if (!node) return false;
    node.click(); return true;
  })()`);

  /** 枚举当前页面可见的可交互控件，标注是否禁用。 */
  const controls = () => evaluate(`(() => {
    const visible = n => { const r = n.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
    const pick = (sel, kind) => [...document.querySelectorAll(sel)].filter(visible).map(n => ({
      kind, text: (n.getAttribute('aria-label') || n.textContent || n.placeholder || n.type || '').trim().replace(/\\s+/g, ' ').slice(0, 60),
      disabled: !!n.disabled, tag: n.tagName.toLowerCase(), type: n.type || ''
    }));
    return [...pick('button', '按钮'), ...pick('input:not([type=hidden])', '输入'),
      ...pick('select', '下拉'), ...pick('textarea', '文本域'), ...pick('summary', '折叠')];
  })()`);

  await cdp('Page.enable'); await cdp('Runtime.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base });
  await waitFor('!!document.querySelector(".workspace")', '应用挂载');
  await delay(1500);

  const report = [];
  const visit = async (label, navText, readyExpression) => {
    pageLabel = label;
    requested.clear(); failed.clear();
    if (navText) {
      const ok = await clickText(navText);
      if (!ok) console.log(`⚠ 找不到导航：${navText}`);
      await delay(1200);
    }
    if (readyExpression) await waitFor(readyExpression, label);
    const items = await controls();
    const text = await evaluate('document.body.innerText.slice(0, 2500)');
    await shot(label);
    report.push({
      page: label,
      controls: items,
      disabled: items.filter(i => i.disabled).map(i => `${i.kind}:${i.text}`),
      endpoints: [...requested.entries()].map(([path, count]) => ({ path, count })).sort((a, b) => a.path.localeCompare(b.path)),
      textSample: text
    });
    console.log(`\n──────── ${label} ────────`);
    console.log(`控件 ${items.length} 个（禁用 ${items.filter(i => i.disabled).length}）`);
    console.log(`请求 ${requested.size} 个接口：`);
    for (const [path] of [...requested.entries()].sort()) console.log(`  ${path}`);
  };

  await visit('01-home', null, '!!document.querySelector(".work-hero")');
  await visit('02-enterprise', '企业问答', '!!document.querySelector(".answer-workbench")');
  await visit('03-knowledge', '知识空间', '!!document.querySelector(".knowledge-answer") === null');
  await visit('04-affairs', '员工事务', '!!document.querySelector(".affairs-panel") || !!document.querySelector(".affair-card")');
  await visit('05-chat', '联网问答', '!!document.querySelector(".composer") || !!document.querySelector("textarea")');

  await writeFile(join(outDir, `${prefix}-report.json`), JSON.stringify({
    report, consoleErrors, endpoints: [...requested.keys()]
  }, null, 2));

  console.log('\n════════ 汇总 ════════');
  console.log(`未捕获异常 / console 报错：${consoleErrors.length}`);
  for (const line of consoleErrors.slice(0, 40)) console.log('  ' + line);
  const all = new Set();
  for (const item of report) for (const ep of item.endpoints) all.add(ep.path);
  console.log(`\n全站请求到的接口路径 ${all.size} 条。`);
} finally {
  try { socket?.close(); } catch {}
  browser.kill();
}
