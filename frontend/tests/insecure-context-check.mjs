import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

/**
 * 用非 localhost 的明文地址（内网 IP）跑真实浏览器，检查：
 * 1. 不安全上下文下两页仍能渲染（crypto.randomUUID 缺失时的回归）；
 * 2. 指定界面语言时，页面还剩多少中文没有词条（i18n 覆盖率）。
 *
 * 用法：
 *   npx vite preview --host 0.0.0.0 --port 4173
 *   CHECK_LANG=en node tests/insecure-context-check.mjs
 * CHECK_LANG 省略时按中文检查渲染。
 */
const ORIGIN = process.env.CHECK_ORIGIN ?? 'http://10.255.95.41:4173';
const LANG = process.env.CHECK_LANG ?? 'zh-CN';
const BROWSER = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const PORT = 9223;

const profile = await mkdtemp(join(tmpdir(), 'edge-cdp-'));
const child = spawn(BROWSER, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--disable-dev-shm-usage',
  'about:blank'
], { stdio: 'ignore' });

async function waitForDevtools() {
  for (let i = 0; i < 60; i += 1) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/version`);
      if (res.ok) return;
    } catch { /* 还没起来 */ }
    await delay(250);
  }
  throw new Error('DevTools 未就绪');
}

let ws;
let seq = 0;
const waiting = new Map();
const errors = [];

function send(method, params = {}) {
  const id = ++seq;
  ws.send(JSON.stringify({ id, method, params }));
  return new Promise((resolve, reject) => waiting.set(id, { resolve, reject }));
}

async function evaluate(expression) {
  const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) return { error: result.exceptionDetails.text };
  return { value: result.result?.value };
}

const click = label => `(() => { const el = [...document.querySelectorAll('button,a')].find(n => (n.textContent || '').trim().includes('${label}')); if (!el) return 'NOT_FOUND'; el.click(); return 'clicked'; })()`;

/** 统计 main 区域里残留的中文片段，用来衡量词条覆盖。 */
const scan = `(() => {
  const main = document.querySelector('main');
  const text = (main?.innerText ?? '').replace(/\\s+/g, ' ').trim();
  const hits = text.match(/[\\u4e00-\\u9fa5][\\u4e00-\\u9fa5A-Za-z0-9，。、；：？！（）()·「」“”…%\\-\\/ ]{1,}/g) ?? [];
  return JSON.stringify({ textLen: text.length, hits: hits.length, sample: hits.slice(0, 12) });
})()`;

try {
  await waitForDevtools();
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
  const page = list.find(item => item.type === 'page');
  ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });

  ws.onmessage = event => {
    const msg = JSON.parse(event.data);
    if (msg.id && waiting.has(msg.id)) {
      const { resolve, reject } = waiting.get(msg.id);
      waiting.delete(msg.id);
      if (msg.error) reject(new Error(msg.error.message)); else resolve(msg.result);
      return;
    }
    if (msg.method === 'Runtime.exceptionThrown') {
      const d = msg.params.exceptionDetails;
      errors.push(`exception: ${d.exception?.description ?? d.text}`);
    }
    if (msg.method === 'Runtime.consoleAPICalled' && ['error', 'assert'].includes(msg.params.type)) {
      errors.push(`console.${msg.params.type}: ${msg.params.args.map(a => a.description ?? a.value).join(' ')}`);
    }
  };

  await send('Runtime.enable');
  await send('Page.enable');
  await send('Page.navigate', { url: ORIGIN });
  await delay(2000);

  if (LANG !== 'zh-CN') {
    await evaluate(`localStorage.setItem('ragqa.preferences.v1', JSON.stringify({
      version: 1, uiLanguage: '${LANG}', answerLanguage: 'follow-ui', timeZone: 'Asia/Shanghai' }))`);
    await send('Page.reload');
    await delay(2200);
  }

  const env = await evaluate('JSON.stringify({ uuid: typeof crypto.randomUUID, secure: window.isSecureContext, lang: document.documentElement.lang })');
  console.log(`运行环境（${LANG}）：`, env.value);

  // 语言是英文时，导航标签变成英文，点击定位要按目标语言找。
  const nav = LANG === 'zh-CN' ? ['企业问答', '员工事务'] : ['Enterprise Q&A', 'Employee affairs'];
  for (const [index, label] of nav.entries()) {
    const clicked = await evaluate(click(label));
    await delay(1600);
    const result = await evaluate(scan);
    console.log(`第${index + 1}页「${label}」点击=${clicked.value}`, result.value ?? result.error);
  }

  console.log('脚本异常：', errors.length ? errors : '无');
} finally {
  try { ws?.close(); } catch { /* ignore */ }
  child.kill('SIGKILL');
  await delay(300);
  await rm(profile, { recursive: true, force: true }).catch(() => {});
}
