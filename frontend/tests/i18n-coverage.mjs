// 英文模式下的中文残留检测：用真实浏览器打开生产构建，逐页统计可见中文。
// 用法：先 `npx vite preview --port 4174`，再 `node tests/i18n-coverage.mjs`
// 约定：用户数据（人名、空间名、文档标题）本身是中文，不计入残留；
//       这里统计的是「界面文案」，做法是排除 .brand / 用户输入区，只看按钮、标签、提示。
import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const ORIGIN = process.env.ORIGIN ?? 'http://127.0.0.1:4175';
const PORT = 9226;
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const han = /[\u4e00-\u9fa5]/;

const profile = await mkdtemp(join(tmpdir(), 'edge-i18n-'));
const child = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--disable-gpu', 'about:blank'], { stdio: 'ignore' });
for (let i = 0; i < 80; i++) {
  try { if ((await fetch(`http://127.0.0.1:${PORT}/json/version`)).ok) break; } catch { /* 等待启动 */ }
  await delay(250);
}
const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
const ws = new WebSocket(list.find(t => t.type === 'page').webSocketDebuggerUrl);
await new Promise(r => { ws.onopen = r; });
let seq = 0;
const waiters = new Map();
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (m.id && waiters.has(m.id)) { const { resolve } = waiters.get(m.id); waiters.delete(m.id); resolve(m.result); }
};
const send = (method, params = {}) => {
  const id = ++seq;
  ws.send(JSON.stringify({ id, method, params }));
  return new Promise(r => waiters.set(id, { resolve: r }));
};
const evaluate = async expression => {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text);
  return r.result?.value;
};

await send('Runtime.enable');
await send('Page.enable');
await send('Page.navigate', { url: ORIGIN });
await delay(1800);
await evaluate(`localStorage.setItem('ragqa.preferences.v1', JSON.stringify({version:1,uiLanguage:'en',answerLanguage:'follow-ui',timeZone:'Asia/Shanghai'}))`);
await send('Page.reload');
await delay(2200);

// 只取「界面文案」：按钮、标签、字段、提示；跳过用户内容容器。
const collect = `(() => {
  const skip = new Set(['SCRIPT','STYLE','TEXTAREA']);
  const out = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  let node;
  while ((node = walker.nextNode())) {
    const text = (node.textContent || '').trim();
    if (!text || !/[\\u4e00-\\u9fa5]/.test(text)) continue;
    const el = node.parentElement;
    if (!el || skip.has(el.tagName)) continue;
    if (el.closest('.brand, .doc-title, .user-content, input')) continue;
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') continue;
    out.push(text.slice(0, 60));
  }
  return JSON.stringify([...new Set(out)]);
})()`;

// 导航按 .nav-item 顺序：0 工作台、1 企业问答、2 知识空间、3 员工事务
const pages = [['工作台', 0], ['企业问答', 1], ['知识空间', 2], ['员工事务', 3]];
console.log('界面语言 =', await evaluate('document.documentElement.lang'));
for (const [label, index] of pages) {
  const clicked = await evaluate(`(() => {
    const items = [...document.querySelectorAll('.nav-item')];
    if (items.length <= ${index}) return 'not-found';
    items[${index}].click();
    return 'clicked:' + (items[${index}].textContent || '').trim().slice(0, 30);
  })()`);
  await delay(1200);
  const rest = JSON.parse(await evaluate(collect));
  console.log(`\n【${label}】点击=${clicked} 残留中文 ${rest.length} 条`);
  for (const text of rest.slice(0, 8)) console.log('   -', text);
}

child.kill('SIGKILL');
await delay(300);
await rm(profile, { recursive: true, force: true }).catch(() => {});
