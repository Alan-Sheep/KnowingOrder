import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

/**
 * 本轮修复的界面回归：费用类型选项、会话删除、检索索引说明。
 * 全部使用拦截响应，不调用真实后端、模型或第三方服务。
 */
const base = process.env.AUDIT_BASE ?? 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outDir = process.env.UI_SCREENSHOT_DIR ?? './test-artifacts/audit';
const prefix = process.env.AUDIT_PREFIX ?? 'fixes';

const me = { code: 0, data: { userId: '961', enterprises: [{ enterpriseId: '1', enterpriseName: '知序科技', role: 'ADMIN' }] } };
const spacePage = { code: 0, data: { records: [{ id: '40', name: '教学教务与学籍', permissionVersion: 3 }], total: 1, pageNum: 1, pageSize: 500, pages: 1 } };
const rules = { code: 0, data: { records: [{ id: '4', name: '差旅补贴（演示）', version: 1, status: 'ACTIVE', period: 'MONTH',
  memberLimitCents: '50000', applicationLimitCents: '20000', requiresManager: 1,
  effectiveFrom: '2026-10-24 17:01:24', effectiveTo: null }], total: 1, pageNum: 1, pageSize: 20, pages: 1 } };
const members = { code: 0, data: [{ memberId: '1', username: 'adminW' }, { memberId: '812', username: 'chenhaoran' }] };
const chats = { code: 0, data: [
  { id: '98053b9e-0b7f-4750-a9c0-1bf4e38f8958', title: '选课', createdMs: 1792000000000 },
  { id: 'fcfdb069-8590-44f3-96b2-35afe503e9e4', title: '好久开学', createdMs: 1792000001000 }
] };
const emptyPage = { code: 0, data: { records: [], total: 0, pageNum: 1, pageSize: 20, pages: 0 } };

await fetch(base).catch(() => { throw new Error('请先运行 npm.cmd run dev。'); });
const profile = await mkdtemp(join(tmpdir(), 'ragqa-fixes-'));
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
  const deletes = [];
  socket.addEventListener('message', event => {
    const value = JSON.parse(event.data);
    if (value.id) {
      const item = pending.get(value.id);
      if (!item) return;
      clearTimeout(item.timer); pending.delete(value.id);
      if (value.error) item.reject(new Error(value.error.message)); else item.resolve(value.result);
    } else if (value.method === 'Fetch.requestPaused') {
      const request = value.params.request;
      const path = new URL(request.url).pathname;
      let payload = emptyPage;
      if (request.method === 'DELETE') { deletes.push(path); payload = { code: 0, data: null }; }
      else if (path === '/api/auth/csrf') payload = { code: 0, data: { headerName: 'X-CSRF-TOKEN', token: 'mock-csrf' } };
      else if (path === '/api/auth/me') payload = me;
      else if (/^\/api\/enterprises\/\d+\/knowledge-spaces$/.test(path)) payload = spacePage;
      else if (path.endsWith('/affairs/rules')) payload = rules;
      else if (path.endsWith('/affairs/members')) payload = members;
      else if (path.endsWith('/workspace/chats')) payload = chats;
      else if (path.endsWith('/answers/mode')) payload = { code: 0, data: 'LOCAL_EVIDENCE' };
      else if (path.endsWith('/answers/retrieval-mode')) payload = { code: 0, data: 'HYBRID' };
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
  const shot = async (name, clip) => {
    await mkdir(outDir, { recursive: true });
    const args = { format: 'png', captureBeyondViewport: false };
    if (clip) args.clip = { ...clip, scale: 1 };
    const { data } = await cdp('Page.captureScreenshot', args);
    await writeFile(join(outDir, `${prefix}-${name}.png`), Buffer.from(data, 'base64'));
    console.log(`  saved ${prefix}-${name}.png`);
  };

  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base });
  await wait('document.querySelector(".workspace") !== null', '应用挂载');

  // ---------- 员工事务：费用类型 ----------
  await click('员工事务');
  await wait('document.querySelector(".affairs-panel") !== null', '员工事务页');
  await delay(1000);
  const options = await evaluate(`(() => {
    const label = [...document.querySelectorAll('.affairs-panel label')].find(node => node.textContent.trim().startsWith('费用类型'));
    const select = label?.querySelector('select');
    return select ? [...select.options].map(o => o.textContent.trim()) : null;
  })()`);
  if (!options) throw new Error('没有找到费用类型下拉');
  for (const expected of ['出差住宿', '交通出行', '活动经费', '举报奖励']) {
    if (!options.some(text => text.includes(expected))) throw new Error(`费用类型缺少「${expected}」，实际：${JSON.stringify(options)}`);
  }
  passed(`费用类型 ${options.length - 1} 项：${options.slice(1).join(' / ')}`);

  // 选「其他」应出现自定义输入
  await evaluate(`(() => {
    const label = [...document.querySelectorAll('.affairs-panel label')].find(node => node.textContent.trim().startsWith('费用类型'));
    const select = label.querySelector('select');
    select.value = 'other';
    select.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  await delay(300);
  const custom = await evaluate(`(() => {
    const input = [...document.querySelectorAll('.affairs-panel input')].find(i => i.placeholder && i.placeholder.includes('实验耗材'));
    return input ? input.placeholder : null;
  })()`);
  if (!custom) throw new Error('选择「其他」后没有出现自定义填写输入框');
  passed('选择「其他」后出现自定义填写框');
  const anchor = await evaluate(`(() => {
    const el = [...document.querySelectorAll('.affairs-panel label')].find(node => node.textContent.trim().startsWith('费用类型'));
    el.scrollIntoView({ block: 'center' });
    return null;
  })()`);
  void anchor;
  await delay(400);
  const box = await evaluate(`(() => {
    const el = [...document.querySelectorAll('.affairs-panel label')].find(node => node.textContent.trim().startsWith('费用类型'));
    const r = el.getBoundingClientRect();
    return { x: Math.max(0, r.x - 40), y: Math.max(0, r.y - 130), width: Math.min(1180, r.width + 700), height: 520 };
  })()`);
  await shot('01-expense-types', box);

  // ---------- 企业问答：删除会话 ----------
  await click('企业问答');
  await wait('document.querySelector(".session-tools") !== null', '会话工具栏');
  await delay(800);
  const hasDelete = await evaluate(`[...document.querySelectorAll('.session-tools button')].some(b => b.textContent.includes('删除会话'))`);
  if (!hasDelete) throw new Error('会话工具栏没有删除入口');
  passed('会话工具栏出现「删除会话」');

  await click('删除会话');
  await wait('document.querySelector(".session-confirm") !== null', '删除确认条');
  const confirmText = await evaluate(`document.querySelector('.session-confirm').textContent.trim()`);
  if (!confirmText.includes('审计')) throw new Error(`确认条没有说明审计保留：${confirmText}`);
  passed('删除前有二次确认，并说明审计仍保留');
  await shot('02-delete-confirm');

  await click('确认删除');
  for (let index = 0; index < 60 && deletes.length === 0; index++) await delay(100);
  if (!deletes.length) throw new Error('没有发出删除请求');
  passed(`已发出删除请求：${deletes[0].split('/').pop()}`);
  await delay(600);
  const remaining = await evaluate(`[...document.querySelectorAll('.session-tools select option')].map(o => o.textContent.trim())`);
  if (remaining.includes('选课')) throw new Error('删除后列表仍显示被删会话');
  passed('删除后列表已移除该会话');

  console.log('DONE');
} finally {
  try { socket?.close(); } catch { /* noop */ }
  browser.kill();
}
