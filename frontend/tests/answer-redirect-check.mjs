import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

/*
 * 答不上来时的去向引导：
 * ① 仅原文摘录（EVIDENCE_ONLY）必须提示「校外的公开信息去公开联网问答」；
 * ② 服务端判定超范围（OUT_OF_SCOPE）要说「不属于当前学校业务范围」；
 * ③ 正常回答（ANSWERED）不许出现这条引导，避免误导。
 * 空间固定为本地检索（LOCAL_EVIDENCE），与真实试点一致：问题不发给模型。
 */
const base = process.env.AUDIT_BASE ?? 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';

const chatId = '3f7a1c2e-5b64-4d8a-9c11-7e2b4a6d8f01';
const jobId = '9d2b4c6e-1a35-4f70-8b92-3c5d7e9a1b02';
const question = '重庆江北什么时候举办跨年晚会';
const me = { code: 0, data: { userId: '1', enterprises: [{ enterpriseId: '1', enterpriseName: '知序科技', role: 'ADMIN' }] } };
const spacePage = { code: 0, data: { records: [{ id: '1', name: '学校教育资料', permissionVersion: 5 }], total: 1, pageNum: 1, pageSize: 10, pages: 1 } };
const chats = { code: 0, data: [{ id: chatId, title: question, createdMs: 1761390000000 }] };
const evidence = {
  chunkId: '101', documentId: '21', versionId: '35', versionNo: 3,
  title: '在读证明与学籍信息变更指引', chunkIndex: 4,
  content: '在籍学生可到校园办事大厅申请带电子签章的在读证明；电子件与校内纸质件采用相同核验编号。',
  startOffset: 0, endOffset: 0, startLine: 1, endLine: 15
};
evidence.endOffset = evidence.content.length;

/** 由用例切换的回答状态。 */
let scenario = 'EVIDENCE_ONLY';
const quote = '在籍学生可到校园办事大厅申请带电子签章的在读证明';
const gaps = {
  EVIDENCE_ONLY: '请阅读下方原文；检索命中不等于答案充分。',
  OUT_OF_SCOPE: '此问题不属于当前学校业务范围。可自行切换到公开联网问答；不会自动发送问题或内部资料。',
  ANSWERED: ''
};
/** 契约规定：本地检索模式只允许 EVIDENCE_ONLY / NO_EVIDENCE / SCOPE_UNAVAILABLE，其余状态须是获准外发模型的空间。 */
const modeFor = status => (status === 'EVIDENCE_ONLY' ? 'LOCAL_EVIDENCE' : 'SYNTHETIC_MODEL');
const answerFor = status => ({
  mode: modeFor(status), status, elapsedMs: 40,
  message: status === 'ANSWERED' ? '已在现有资料中找到可核对的结论。' : '已找到相关原文，仅供核对；尚未生成或判定最终答案。',
  evidence: [evidence],
  parts: [{
    id: 1, question, status,
    paragraphs: status === 'ANSWERED'
      ? [{ text: '资料显示在籍学生可在校园办事大厅申请在读证明。', citations: [{ sourceId: 1, quote, evidence }] }] : [],
    gaps: status === 'ANSWERED' ? [] : [gaps[status]]
  }]
});

const profile = await mkdtemp(join(tmpdir(), 'redirect-'));
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
    const timer = setTimeout(() => reject(new Error(`超时：${method}`)), 20000);
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
      return;
    }
    if (value.method !== 'Fetch.requestPaused') return;
    const path = new URL(value.params.request.url).pathname;
    let payload = { code: 0, data: null };
    if (path === '/api/auth/me') payload = me;
    else if (path === '/api/auth/csrf') payload = { code: 0, data: { headerName: 'X-CSRF-TOKEN', token: 'csrf-redirect' } };
    else if (/^\/api\/enterprises\/\d+\/knowledge-spaces$/.test(path)) payload = spacePage;
    else if (/answers\/mode$/.test(path)) payload = { code: 0, data: modeFor(scenario) };
    else if (/answers\/retrieval-mode$/.test(path)) payload = { code: 0, data: 'HYBRID' };
    else if (/\/workspace\/chats$/.test(path)) payload = chats;
    else if (/\/workspace\/chats\/[0-9a-f-]{36}$/.test(path)) payload = { code: 0, data: [{
      id: jobId, chatId, question, status: 'COMPLETED',
      createdMs: 1761391500000, deadlineMs: 1761391530000, finishedMs: 1761391502400,
      answer: answerFor(scenario), message: '已完成检索与核对。'
    }] };
    else if (/\/answers\/evidence\/\d+$/.test(path)) payload = { code: 0, data: evidence };
    cdp('Fetch.fulfillRequest', { requestId: value.params.requestId, responseCode: 200,
      responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
      body: Buffer.from(JSON.stringify(payload)).toString('base64') }).catch(() => {});
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

  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

  const cases = [
    { status: 'EVIDENCE_ONLY', expect: '校外', label: '仅原文摘录' },
    { status: 'OUT_OF_SCOPE', expect: '不属于当前学校的业务范围', label: '超出范围' },
    { status: 'ANSWERED', expect: null, label: '正常回答' }
  ];
  for (const item of cases) {
    scenario = item.status;
    await cdp('Page.navigate', { url: base });
    await wait('document.querySelector(".workspace") !== null', '应用挂载');
    await click('企业问答');
    await wait(`document.querySelector('.answer-card') !== null`, `${item.label} 回答渲染`);
    await delay(300);
    const text = await evaluate(`document.querySelector('.answer-scope-switch')?.textContent.replace(/\\s+/g, ' ').trim() ?? ''`);
    if (item.expect) {
      assert.ok(text.includes(item.expect), `${item.label} 引导文案不符：${text || '(没有出现引导)'}`);
      assert.ok(text.includes('公开联网问答'), `${item.label} 没有指向公开问答：${text}`);
    } else {
      assert.equal(text, '', `${item.label} 不该出现“答不上来”的引导：${text}`);
    }
    console.log(`  ${item.label}：${text ? text.slice(0, 42) + '…' : '无引导'}`);
  }

  console.log('answer-redirect-check 通过：仅原文摘录/超出范围会引导到公开问答，正常回答不误导');
} finally {
  try { socket?.close(); } catch { /* noop */ }
  browser.kill();
}
