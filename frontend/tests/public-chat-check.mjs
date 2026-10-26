import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

/*
 * 公开联网问答入口的"未开启"状态回归。
 *
 * 这一页只在生产构建里出现（`publicChatEnabled` 依赖 `import.meta.env.DEV`），
 * 所以必须对着 `vite preview` 或真实部署验证，不能对着 `vite dev` 跑。
 *
 * 断言三件事：
 * 1. 侧栏入口仍然存在（不能因为关闭功能把导航项删掉）；
 * 2. 点进去显示说明态，而不是空白或报错页；
 * 3. 说明态给出两条真能走的路径（企业问答、公开搜索引擎），且没有可提交的输入框。
 */
const base = process.env.AUDIT_BASE ?? 'http://127.0.0.1:4173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outDir = process.env.UI_SCREENSHOT_DIR ?? './test-artifacts/audit';
const width = Number(process.env.AUDIT_WIDTH ?? 1440);
const height = Number(process.env.AUDIT_HEIGHT ?? 900);

await fetch(base).catch(() => { throw new Error('请先运行 npx vite preview --port 4173（需要 dist 产物）。'); });
await mkdir(outDir, { recursive: true });

const profile = await mkdtemp(join(tmpdir(), 'ragqa-parked-'));
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
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });
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
    if (!value.id) return;
    const item = pending.get(value.id);
    if (!item) return;
    clearTimeout(item.timer);
    pending.delete(value.id);
    if (value.error) item.reject(new Error(value.error.message)); else item.resolve(value.result);
  });
  const evaluate = async expression => {
    const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text ?? '脚本执行失败');
    return result.result.value;
  };
  const wait = async (expression, label) => {
    for (let attempt = 0; attempt < 120; attempt++) {
      if (await evaluate(expression)) return;
      await delay(100);
    }
    throw new Error(`超时：${label}`);
  };
  const passed = [];
  const check = (name, condition, detail = '') => {
    assert.ok(condition, `${name}${detail ? `：${detail}` : ''}`);
    passed.push(name);
  };

  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base });
  await wait('document.querySelector(".nav-item") !== null', '侧栏渲染');

  // ① 侧栏入口必须还在：关闭功能不等于删除入口，否则界面会显得缺一块。
  const hasEntry = await evaluate(`[...document.querySelectorAll('.nav-item')]
    .some(node => node.textContent.includes('联网问答体验'))`);
  check('侧栏保留联网问答入口', hasEntry);

  // ② 点进去应该是说明态，不是空白页，也不是可提交的输入框。
  await evaluate(`(() => {
    const target = [...document.querySelectorAll('.nav-item')]
      .find(node => node.textContent.includes('联网问答体验'));
    target.click();
    return true;
  })()`);
  await wait('document.querySelector(".chat-parked") !== null', '未开启说明态');
  const parked = await evaluate(`(() => {
    const node = document.querySelector('.chat-parked');
    return {
      title: node.querySelector('h1')?.textContent?.trim() ?? '',
      actions: [...node.querySelectorAll('button, a')].map(item => item.textContent.trim())
    };
  })()`);
  check('说明态标题正确', parked.title.includes('未开启'), parked.title);
  check('未渲染可提交的输入框', (await evaluate('document.querySelector(".composer") === null')));
  check('企业问答入口可用', parked.actions.some(text => text.includes('前往企业问答')), parked.actions.join(' / '));
  check('公开搜索引擎入口可用', parked.actions.some(text => text.includes('公开搜索引擎')), parked.actions.join(' / '));

  // ③ 顶部工具（导出/参考资料）不应出现在未开启状态，避免点了没反应。
  check('未开启时不显示导出与参考资料', (await evaluate(`document.querySelector('.context-toggle') === null`)));

  // 等页面切换动画结束再截图，否则会拍到上一页正在退出的残影。
  await delay(700);
  const shot = await cdp('Page.captureScreenshot', { format: 'png' });  await writeFile(join(outDir, 'parked-01-chat-disabled.png'), Buffer.from(shot.data, 'base64'));
  console.log('检查通过：' + passed.map(item => `\n  ✓ ${item}`).join(''));
  console.log(`截图：${join(outDir, 'parked-01-chat-disabled.png')}`);
  console.log('DONE');
} finally {
  try { socket?.close(); } catch { /* 忽略关闭异常 */ }
  browser.kill();
}
