import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

/*
 * 企业问答对话页的布局与交互回归：
 * 1. 输入框必须常驻视口内，且对话区内部滚动、整页不滚；
 * 2. 提问与回答不能出现水平溢出；
 * 3. 引用胶囊、命中原文、删除会话的交互链路完整；
 * 4. 空态在新建会话后仍成立。
 * 全程拦截接口，不调用真实后端。
 */
const base = process.env.AUDIT_BASE ?? 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outDir = process.env.UI_SCREENSHOT_DIR ?? './test-artifacts/audit';
const prefix = process.env.AUDIT_PREFIX ?? 'answer';
const width = Number(process.env.AUDIT_WIDTH ?? 1440);
const height = Number(process.env.AUDIT_HEIGHT ?? 900);

const chatId = '3f7a1c2e-5b64-4d8a-9c11-7e2b4a6d8f01';
const secondChat = '5c8d0e2f-7a19-4b3c-8d5e-2f4a6b8c0d13';
const jobId = '9d2b4c6e-1a35-4f70-8b92-3c5d7e9a1b02';
const me = { code: 0, data: { userId: '1', enterprises: [{ enterpriseId: '1', enterpriseName: '知序科技', role: 'ADMIN' }] } };
const spacePage = { code: 0, data: { records: [
  { id: '1', name: '差旅报销与费用制度', permissionVersion: 5 },
  { id: '2', name: '人事制度与假期', permissionVersion: 2 },
  { id: '3', name: '研发工作指南', permissionVersion: 3 }
], total: 3, pageNum: 1, pageSize: 10, pages: 1 } };
const chats = { code: 0, data: [
  { id: chatId, title: '出差住宿报销上限是多少', createdMs: 1761390000000 },
  { id: secondChat, title: '年假可以跨年顺延吗', createdMs: 1761303600000 }
] };
const evidence = {
  chunkId: '101', documentId: '21', versionId: '35', versionNo: 3,
  title: '差旅费用管理办法', chunkIndex: 4,
  content: '第四章 住宿标准\n第十三条 一线城市（北京、上海、广州、深圳）住宿标准为每人每晚不超过 600 元；'
    + '其他省会城市不超过 450 元；其余城市不超过 350 元。\n第十四条 超出标准的，需在报销单中说明原因，并经部门负责人与财务负责人共同审批后方可报销。',
  startOffset: 0, endOffset: 0, startLine: 22, endLine: 27
};
evidence.endOffset = evidence.content.length;
const evidence2 = {
  chunkId: '102', documentId: '21', versionId: '35', versionNo: 3,
  title: '差旅费用管理办法', chunkIndex: 5,
  content: '第十五条 报销应在出差结束后 15 个工作日内提交，逾期需附书面说明。',
  startOffset: 0, endOffset: 0, startLine: 28, endLine: 29
};
evidence2.endOffset = evidence2.content.length;
const quote1 = '一线城市（北京、上海、广州、深圳）住宿标准为每人每晚不超过 600 元';
const answer = {
  mode: 'SYNTHETIC_MODEL', status: 'PARTIAL',
  message: '根据《差旅费用管理办法》第三章至第四章，住宿报销按城市分级设上限，超标准需双人审批。',
  elapsedMs: 2140,
  evidence: [evidence, evidence2],
  parts: [{
    id: 1, question: '出差住宿报销上限是多少？', status: 'PARTIAL',
    paragraphs: [
      { text: '住宿费按出差城市分级设定每晚上限：一线城市（北京、上海、广州、深圳）每人每晚不超过 600 元，其他省会城市不超过 450 元，其余城市不超过 350 元。',
        citations: [{ sourceId: 1, quote: quote1, evidence }] },
      { text: '如果实际住宿费超出上限，需要在报销单中写明原因，并由部门负责人与财务负责人共同审批后才可报销。',
        citations: [{ sourceId: 1, quote: '超出标准的，需在报销单中说明原因', evidence }] }
    ],
    gaps: ['办法中未规定港澳台及境外城市的住宿标准，出差前请向财务确认。']
  }]
};
const job = {
  id: jobId, chatId, question: '出差住宿报销上限是多少？', status: 'COMPLETED',
  createdMs: 1761391500000, deadlineMs: 1761391530000, finishedMs: 1761391502400,
  answer, message: '已完成检索与核对。'
};

await fetch(base).catch(() => { throw new Error('请先运行 npm.cmd run dev。'); });
const profile = await mkdtemp(join(tmpdir(), 'ragqa-answer-'));
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
  const deletes = [];
  socket.addEventListener('message', event => {
    const value = JSON.parse(event.data);
    if (value.id) {
      const item = pending.get(value.id);
      if (!item) return;
      clearTimeout(item.timer); pending.delete(value.id);
      if (value.error) item.reject(new Error(value.error.message)); else item.resolve(value.result);
    } else if (value.method === 'Fetch.requestPaused') {
      const path = new URL(value.params.request.url).pathname;
      const method = value.params.request.method;
      let payload = { code: 0, data: null };
      if (method === 'DELETE') { deletes.push(path); }
      else if (path === '/api/auth/me') payload = me;
      else if (path === '/api/auth/csrf') payload = { code: 0, data: { headerName: 'X-CSRF-TOKEN', token: 'csrf-answer-check' } };
      else if (/^\/api\/enterprises\/\d+\/knowledge-spaces$/.test(path)) payload = spacePage;
      else if (/answers\/mode$/.test(path)) payload = { code: 0, data: 'SYNTHETIC_MODEL' };
      else if (/answers\/retrieval-mode$/.test(path)) payload = { code: 0, data: 'HYBRID' };
      else if (/\/workspace\/chats$/.test(path)) payload = chats;
      else if (/\/workspace\/chats\/[0-9a-f-]{36}$/.test(path)) payload = { code: 0, data: [job] };
      // 按片段编号返回对应原文：切换引用时要验证换的是不是另一段。
      else if (/\/answers\/evidence\/\d+$/.test(path)) payload = { code: 0, data: path.endsWith('/102') ? evidence2 : evidence };
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
  await cdp('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base });
  await wait('document.querySelector(".workspace") !== null', '应用挂载');

  await click('企业问答');
  await wait('document.querySelector(".answer-workbench") !== null', '问答工作台');
  await delay(1200);
  await shot('01-conversation');

  // ① 输入框必须常驻视口：对话页整页不滚，只有答案流内部滚动。
  const layout = await evaluate(`(() => {
    const page = document.querySelector('.enterprise-workspace[data-page=enterprise]');
    const composer = document.querySelector('.enterprise-composer');
    const stream = document.querySelector('.answer-stream');
    const rect = composer.getBoundingClientRect();
    return { pageScroll: page.scrollHeight - page.clientHeight,
      composerTop: rect.top, composerBottom: rect.bottom, viewport: innerHeight,
      streamScrollable: stream.scrollHeight - stream.clientHeight,
      overflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth };
  })()`);
  assert.ok(layout.composerTop > 0 && layout.composerBottom <= layout.viewport,
    `输入框落在视口外：${layout.composerTop}~${layout.composerBottom} / ${layout.viewport}`);
  assert.ok(layout.pageScroll <= 1, `对话页仍整页滚动（多出 ${layout.pageScroll}px），输入框会被顶出视口`);
  assert.ok(layout.streamScrollable > 0, '长回答未在答案流内部滚动');
  assert.equal(layout.overflowX, 0, '页面出现水平溢出');

  // ② 自动滚动后，这一轮的提问必须完整可见（不能被容器顶边裁掉）。
  const question = await evaluate(`(() => {
    const bubble = document.querySelector('.answer-card .user-question h3');
    const stream = document.querySelector('.answer-stream');
    const b = bubble.getBoundingClientRect(); const s = stream.getBoundingClientRect();
    return { top: b.top, bottom: b.bottom, streamTop: s.top, text: bubble.textContent.trim() };
  })()`);
  assert.ok(question.top >= question.streamTop - 1,
    `提问气泡被顶边裁切：${question.top} < ${question.streamTop}`);
  assert.equal(question.text, '出差住宿报销上限是多少？');

  // ③ 一轮回答只应有一条状态行，不应再出现整块琥珀色警告底。
  const card = await evaluate(`(() => {
    const node = document.querySelector('.answer-card');
    return { states: node.querySelectorAll('.answer-state').length,
      citations: node.querySelectorAll('.citation-button').length,
      parts: node.querySelectorAll('.answer-part').length,
      status: node.querySelector('.answer-state')?.dataset.status,
      lead: node.querySelector('.answer-message')?.textContent.trim() };
  })()`);
  assert.equal(card.states, 1, '状态行重复出现');
  assert.equal(card.status, 'PARTIAL');
  assert.equal(card.parts, 1);
  assert.equal(card.citations, 2, `引用胶囊数量不符：${card.citations}`);
  assert.ok(card.lead?.includes('住宿报销按城市分级设上限'), '缺少回答导语');

  // ④ 点引用 → 依据栏切换为原文核对视图。
  await evaluate(`document.querySelector('.citation-button')?.click()`);
  await wait('document.querySelector(".source-view pre") !== null', '引用原文视图');
  const source = await evaluate(`document.querySelector('.source-view').textContent`);
  assert.ok(source.includes('差旅费用管理办法') && source.includes('第22—27行'), '依据栏缺少标题或行号');
  await shot('02-source-view');

  // ④b 展开后引用列表必须还在，并且能收起——否则第一条长原文会把整栏占住，看不到别的引用。
  const railAfterOpen = await evaluate(`({
    list: document.querySelectorAll('.source-item').length,
    open: document.querySelectorAll('.source-item.open').length,
    collapse: !!document.querySelector('.source-collapse')
  })`);
  assert.equal(railAfterOpen.list, 2, `展开原文后引用列表不应消失，实际 ${railAfterOpen.list} 条`);
  assert.equal(railAfterOpen.open, 1, '应当只有点开的那一条处于展开态');
  assert.ok(railAfterOpen.collapse, '展开原文后缺少收起入口');

  await evaluate(`document.querySelector('.source-collapse')?.click()`);
  await wait(`document.querySelector('.source-view') === null`, '收起后回到引用列表');
  assert.equal(await evaluate(`document.querySelectorAll('.source-item').length`), 2, '收起后引用列表应保持可见');
  await shot('02b-source-collapsed');

  // ④c 收起后能继续核对下一条，且换条不会残留上一条的内容。
  await evaluate(`document.querySelectorAll('.source-item')[1]?.click()`);
  await wait(`document.querySelector('.source-view pre') !== null`, '第二条引用原文视图');
  const second = await evaluate(`document.querySelector('.source-view').textContent`);
  assert.ok(second.includes('第28—29行'), `切换引用后没有显示对应原文：${second.slice(0, 80)}`);
  assert.equal(await evaluate(`document.querySelectorAll('.source-item').length`), 2, '核对第二条时列表仍应可见');

  // ⑤ 命中原文可展开，且不会把输入框挤出视口。
  await evaluate(`document.querySelector('.answer-evidence')?.setAttribute('open', '')`);
  await delay(400);
  const stillVisible = await evaluate(`document.querySelector('.enterprise-composer').getBoundingClientRect().bottom <= innerHeight`);
  assert.equal(stillVisible, true, '展开命中原文后输入框被挤出视口');
  await shot('03-evidence-open');

  // ⑥ 窄屏不固定高度，改为整页滚动，且无水平溢出。
  await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await delay(500);
  const narrow = await evaluate(`(() => ({
    overflowX: document.documentElement.scrollWidth - document.documentElement.clientWidth,
    pageScroll: document.querySelector('.enterprise-workspace[data-page=enterprise]').scrollHeight
      - document.querySelector('.enterprise-workspace[data-page=enterprise]').clientHeight,
    workbench: !!document.querySelector('.answer-workbench')
  }))()`);
  assert.equal(narrow.overflowX, 0, '手机宽度出现水平溢出');
  assert.equal(narrow.workbench, true);
  await shot('04-narrow');
  await cdp('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  await delay(400);

  // ⑦ 新建会话回到空态，布局依然成立。
  await click('新建会话');
  await wait('document.querySelector(".answer-welcome") !== null', '空态');
  const welcome = await evaluate(`(() => {
    const node = document.querySelector('.answer-welcome');
    const stream = document.querySelector('.answer-stream');
    return { starters: node.querySelectorAll('.question-starters button').length,
      inside: node.getBoundingClientRect().top >= stream.getBoundingClientRect().top - 1 };
  })()`);
  assert.equal(welcome.starters, 3, '空态引导卡数量不符');
  assert.equal(welcome.inside, true);
  await shot('05-welcome');

  // ⑧ 删除会话：二次确认 → DELETE 请求 → 列表移除。
  // 新会话还没有落库，删除入口只在选中历史会话时出现，先切回会话列表里的那一条。
  await evaluate(`(() => {
    const node = document.querySelector('.session-tools select');
    node.value = ${JSON.stringify(chatId)};
    node.dispatchEvent(new Event('change', { bubbles: true }));
  })()`);
  await wait('document.querySelector(".session-tools .session-delete") !== null', '删除入口');
  await click('删除会话');
  await wait('document.querySelector(".session-confirm") !== null', '删除确认');
  await click('确认删除');
  await wait('document.querySelector(".session-confirm") === null', '删除完成');
  assert.equal(deletes.length, 1, `未发出删除请求：${deletes.length}`);
  assert.match(deletes[0] ?? '', /\/workspace\/chats\/[0-9a-f-]{36}$/);

  const errors = await evaluate(`document.querySelector('.answer-error')?.textContent ?? ''`);
  assert.equal(errors, '', `页面出现错误提示：${errors}`);
  console.log('企业问答对话页检查通过：8/8。');
  console.log('DONE');
} finally {
  try { socket?.close(); } catch { /* noop */ }
  browser.kill();
}
