import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

// 仅用于人工核对当前界面视觉现状，不调用真实后端或第三方服务。
const base = process.env.AUDIT_BASE ?? 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outDir = process.env.UI_SCREENSHOT_DIR ?? './test-artifacts/audit';
const width = Number(process.env.AUDIT_WIDTH ?? 1440);
const height = Number(process.env.AUDIT_HEIGHT ?? 900);
const prefix = process.env.AUDIT_PREFIX ?? 'before';

await fetch(base).catch(() => { throw new Error('请先运行 npm.cmd run dev。'); });
const profile = await mkdtemp(join(tmpdir(), 'ragqa-audit-'));
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
  socket.addEventListener('message', event => {
    const value = JSON.parse(event.data);
    if (value.id) {
      const item = pending.get(value.id);
      if (!item) return;
      clearTimeout(item.timer); pending.delete(value.id);
      if (value.error) item.reject(new Error(value.error.message)); else item.resolve(value.result);
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
  await cdp('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base });
  await wait('document.querySelector(".workspace") !== null', '应用挂载');

  await shot('01-home');
  await click('企业问答');
  await delay(700);
  await shot('02-enterprise');
  await click('知识空间');
  await delay(700);
  await shot('03-knowledge');
  await click('员工事务');
  await delay(700);
  await shot('04-affairs');
  await click('联网问答体验');
  await delay(700);
  await shot('05-public-chat');
  console.log('DONE');
} finally {
  try { socket?.close(); } catch { /* noop */ }
  browser.kill();
}
