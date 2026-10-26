import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

/*
 * 工作台概览与事务页韧性回归：
 * ① 工作台必须真的读接口，而不是只摆导航按钮；
 * ② 顶栏不应再出现 PREVIEW 这类开发期标记；
 * ③ 事务页任一区块接口失败，其余区块照常渲染，并提供局部重试。
 * 全程拦截接口，不调用真实后端。
 */
const base = process.env.AUDIT_BASE ?? 'http://127.0.0.1:5173';
const browserPath = process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outDir = process.env.UI_SCREENSHOT_DIR ?? './test-artifacts/audit';
const prefix = process.env.AUDIT_PREFIX ?? 'workbench';
const width = Number(process.env.AUDIT_WIDTH ?? 1440);
const height = Number(process.env.AUDIT_HEIGHT ?? 900);

const appIdA = 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d';
const appIdB = 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e';
const me = { code: 0, data: { userId: '1', enterprises: [{ enterpriseId: '1', enterpriseName: '知序科技', role: 'ADMIN' }] } };
const spacePage = { code: 0, data: { records: [{ id: '1', name: '差旅报销与费用制度', permissionVersion: 5 }], total: 3, pageNum: 1, pageSize: 1, pages: 3 } };
const rule = {
  id: '1', name: '差旅报销规则', version: 2, status: 'ACTIVE', period: 'MONTH',
  memberLimitCents: '200000', applicationLimitCents: '20000', requiresManager: true,
  effectiveFrom: '2026-01-01 00:00:00', effectiveTo: null
};
const pendingRow = {
  participantId: '11', applicationId: appIdA, ruleName: '差旅报销规则', platform: 'travel-hotel',
  occurredAt: '2026-10-20 10:00:00', amountCents: '60000', version: 1, shareCents: '30000', createdMs: 1761390000000
};
const summaryRow = {
  id: appIdB, ruleName: '差旅报销规则', platform: 'travel-hotel', occurredAt: '2026-10-21 09:30:00',
  amountCents: '60000', status: 'CONFIRMING', currentVersion: 1, createdMs: 1761440000000
};
const pageOf = (records, total, pageSize = 20) =>
  ({ code: 0, data: { records, total, pageNum: 1, pageSize, pages: Math.ceil(total / pageSize) } });

const requestLog = [];
let mode = 'ok';

const profile = await mkdtemp(join(tmpdir(), 'wb-'));
const browser = spawn(browserPath, ['--headless=new', '--disable-gpu', '--no-first-run', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore', windowsHide: true });
let socket;
try {
  let port;
  for (let index = 0; index < 150; index++) {
    try { port = Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); break; } catch { await delay(100); }
  }
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl);
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
    const message = JSON.parse(event.data);
    if (message.id) {
      const item = pending.get(message.id);
      if (!item) return;
      clearTimeout(item.timer);
      pending.delete(message.id);
      message.error ? item.reject(new Error(message.error.message)) : item.resolve(message.result);
      return;
    }
    if (message.method !== 'Fetch.requestPaused') return;
    const path = new URL(message.params.request.url).pathname;
    requestLog.push(path);
    let payload = { code: 0, data: null };
    if (path === '/api/auth/me') payload = me;
    else if (path === '/api/auth/csrf') payload = { code: 0, data: { headerName: 'X-CSRF-TOKEN', token: 'csrf-workbench' } };
    else if (/\/knowledge-spaces$/.test(path)) payload = spacePage;
    else if (/\/affairs\/members$/.test(path)) payload = { code: 0, data: [{ memberId: '1', username: '张岚' }, { memberId: '2', username: '陈屿' }] };
    else if (/\/affairs\/finance\/applications$/.test(path)) payload = pageOf([], 0);
    else if (/\/affairs\/settlements$/.test(path)) payload = pageOf([], 0);
    else if (/\/affairs\/confirmations$/.test(path)) payload = pageOf([pendingRow], 2);
    else if (/\/affairs\/applications$/.test(path)) payload = pageOf([summaryRow], 5);
    else if (/\/affairs\/rules$/.test(path)) payload = mode === 'broken'
      // 规则结构被改坏：合规的 records 外壳 + 缺字段的一行。
      ? { code: 0, data: { records: [{ id: '1' }], total: 1, pageNum: 1, pageSize: 20, pages: 1 } }
      : pageOf([rule], 1);
    cdp('Fetch.fulfillRequest', {
      requestId: message.params.requestId, responseCode: 200,
      responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
      body: Buffer.from(JSON.stringify(payload)).toString('base64')
    }).catch(() => {});
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
    console.log(`saved ${prefix}-${name}.png`);
  };

  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] });
  await cdp('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base });
  await wait('document.querySelector(".work-overview") !== null', '工作台概览');
  await wait('document.querySelectorAll(".overview-card").length === 3', '三个概览卡片');
  await delay(700);
  await shot('01-home');

  // ① 概览必须来自接口：空间 3 个、待确认 2 条、我的申请 5 条且带最新状态。
  const overview = await evaluate(`(() => {
    const cards = [...document.querySelectorAll('.overview-card')].map(node => ({
      label: node.querySelector('small')?.textContent.trim(),
      value: node.querySelector('strong')?.textContent.trim(),
      hint: node.querySelector('.overview-body > span')?.textContent.trim()
    }));
    return { cards, scope: document.querySelector('.overview-scope')?.textContent.replace(/\\s+/g, ' ').trim() };
  })()`);
  assert.equal(overview.cards.length, 3, '概览卡片数量不符');
  assert.equal(overview.cards[0].label, '可访问知识空间');
  assert.equal(overview.cards[0].value, '3个', `空间数量未接后端：${overview.cards[0].value}`);
  assert.equal(overview.cards[1].value, '2条', `待确认数量未接后端：${overview.cards[1].value}`);
  assert.equal(overview.cards[2].value, '5条', `我的申请数量未接后端：${overview.cards[2].value}`);
  assert.ok(overview.cards[2].hint.includes('待参与人确认'), `最新申请状态未展示：${overview.cards[2].hint}`);
  assert.ok(overview.scope.includes('知序科技'), '缺少企业归属说明');

  // ② 顶栏不应再挂着开发期标记。
  const leaked = await evaluate(`['PREVIEW', '待接入', '即将上线'].filter(word => document.body.textContent.includes(word))`);
  assert.deepEqual(leaked, [], `顶栏仍显示开发期标记：${leaked.join('、')}`);

  // ③ 事务页：规则接口结构被改坏时，其余区块必须照常显示。
  mode = 'broken';
  requestLog.length = 0;
  await click('员工事务');
  await wait('document.querySelector(".affairs-panel") !== null', '事务面板');
  await delay(1500);
  await evaluate(`document.querySelector('.affair-compose')?.scrollIntoView({ block: 'start' })`);
  await delay(400);
  await shot('02-affairs-degraded');

  const tried = ['/affairs/rules', '/affairs/members', '/affairs/applications', '/affairs/confirmations', '/affairs/finance/applications', '/affairs/settlements'];
  const missing = tried.filter(path => !requestLog.some(item => item.endsWith(path)));
  assert.deepEqual(missing, [], `规则失败后这些接口没有继续请求：${missing.join('、')}`);

  const degraded = await evaluate(`(() => ({
    rows: document.querySelectorAll('.affair-row').length,
    notices: [...document.querySelectorAll('.retry-notice')].map(node => node.textContent.replace(/\\s+/g, ' ').trim()),
    ruleOptions: document.querySelector('.affair-compose select')?.options.length ?? 0,
    members: document.querySelectorAll('.share-row select option').length
  }))()`);
  assert.ok(degraded.rows >= 1, '规则接口失败后，「我的申请」仍然空白——区块隔离没生效');
  assert.ok(degraded.notices.some(text => text.includes('补贴规则')), '规则区块没有局部重试提示');
  assert.ok(degraded.members > 1, '成员名单没渲染，成员选择应该独立于规则加载');
  assert.equal(degraded.ruleOptions, 1, '规则结构非法时不应渲染任何规则选项');

  // ④ 区块内重试：规则恢复后提示消失，选项出现，其它区块不受影响。
  mode = 'ok';
  await evaluate(`([...document.querySelectorAll('.retry-notice button')].find(node => node.textContent.includes('补贴规则')))?.click()`);
  await wait('document.querySelector(".retry-notice") === null', '规则区块重试成功');
  const recovered = await evaluate(`(() => ({
    ruleOptions: document.querySelector('.affair-compose select')?.options.length ?? 0,
    rows: document.querySelectorAll('.affair-row').length
  }))()`);
  assert.ok(recovered.ruleOptions >= 2, '重试后规则选项没有出现');
  assert.ok(recovered.rows >= 1, '重试后「我的申请」被清空');
  await shot('03-affairs-recovered');

  console.log('workbench-check 通过：概览 3 项接后端 / 无开发期标记 / 区块隔离与局部重试');
} finally {
  try { socket?.close(); } catch { /* 关闭失败不影响结论 */ }
  browser.kill();
}
