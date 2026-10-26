import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { setTimeout as delay } from 'node:timers/promises';
import { checkOrganizationUi } from './support/organization-ui.mjs';
import { checkOfficeLive } from './support/knowledge-office-live.mjs';

// 默认拦截企业接口；--live使用本机后端并由用户亲自登录，不读取其密码/Cookie/IDE配置。
const liveAnswer = process.argv.includes('--live-answer');
const liveWorkspace = process.argv.includes('--live-workspace');
const liveOfficePermissions = process.argv.includes('--live-office-permissions');
const liveOffice = process.argv.includes('--live-office') || liveOfficePermissions;
assert.ok(!liveOffice || !process.argv.some(value => ['--live-answer', '--live-workspace', '--organization', '--live-organization'].includes(value)), '文件与改密验收不可混用其他验收模式');
const organizationMode = process.argv.includes('--organization') || process.argv.includes('--live-organization');
assert.ok(!organizationMode || (!liveAnswer && !liveWorkspace), '组织验收不可混用问答或模型验收模式');
assert.ok(!(liveAnswer && liveWorkspace), '本机工作区验收不能同时开启真实模型验收');
const live = process.argv.includes('--live') || liveAnswer || liveWorkspace || liveOffice || process.argv.includes('--live-organization');
const acceptanceSpace = process.env.KNOWLEDGE_ACCEPTANCE_SPACE_ID;
if (liveAnswer) assert.match(acceptanceSpace ?? '', /^[1-9]\d*$/, '真实回答验收须明确指定专用虚构资料空间');
const base = 'http://localhost:5173';
const profile = await mkdtemp(join(tmpdir(), 'knowingorder-enterprise-'));
const browser = spawn(process.env.BROWSER_PATH ?? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  [...(live ? [] : ['--headless=new']), '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'],
  { stdio: 'ignore', windowsHide: !live });
let socket;
let launchError;
let cdp;
let cleanupLive;
browser.on('error', error => { launchError = error; });
let checks = 0;
const passed = text => console.log(`通过 ${++checks}：${text}`);
try {
  let port;
  for (let index = 0; index < 150; index++) {
    if (launchError) throw launchError;
    try { port = Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); break; }
    catch { await delay(100); }
  }
  assert.ok(port, '浏览器启动超时');
  const pages = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket = new WebSocket(pages.find(page => page.type === 'page').webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
  let serial = 0;
  let loadCount = 0;
  const pending = new Map();
  const exceptions = [];
  const interceptionErrors = [];
  let onEvent = () => {};
  socket.addEventListener('message', event => {
    const value = JSON.parse(event.data);
    if (value.id) {
      const item = pending.get(value.id);
      if (!item) return;
      pending.delete(value.id);
      clearTimeout(item.timer);
      if (value.error) item.reject(new Error(value.error.message)); else item.resolve(value.result);
    } else {
      if (value.method === 'Page.loadEventFired') loadCount++;
      if (value.method === 'Runtime.exceptionThrown') exceptions.push(value.params.exceptionDetails.text);
      onEvent(value);
    }
  });
  cdp = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++serial;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`浏览器命令超时：${method}`)); }, 12000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async expression => {
    const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (result.exceptionDetails) throw new Error('页面执行失败，请查看本机浏览器');
    return result.result.value;
  };
  const wait = async (expression, attempts = 200) => {
    for (let index = 0; index < attempts; index++) { if (await evaluate(expression)) return; await delay(100); }
    throw new Error(`页面等待超时：${expression}`);
  };
  const fill = (id, text) => evaluate(`(() => { const node=document.getElementById(${JSON.stringify(id)}); node.value=${JSON.stringify(text)}; node.dispatchEvent(new Event('input',{bubbles:true})); })()`);
  const reload = async () => {
    const before = loadCount;
    await cdp('Page.reload', { ignoreCache: true });
    for (let index = 0; index < 200 && loadCount === before; index++) await delay(50);
    assert.ok(loadCount > before, '刷新未完成，禁止对旧页面执行后续断言');
  };
  const submit = label => evaluate(`document.querySelector('form[aria-label=${JSON.stringify(label)}]').requestSubmit()`);
  const navigateKnowledge = () => evaluate('[...document.querySelectorAll(".primary-nav button")].find(node=>node.textContent.includes("知识空间")).click()');
  const chooseWorkspace = async (enterprise, answer = false) => {
    const selector = answer ? '#answer-workspace' : '#enterprise-workspace';
    await wait(answer ? 'document.querySelector("#answer-workspace")?.disabled === false || document.querySelector(".scope-refresh")?.disabled === false'
      : 'document.querySelector(".account-line > button")?.disabled === false');
    if (await evaluate(`document.querySelector(${JSON.stringify(selector)}) !== null`)) {
      await evaluate(`(() => { const node=document.querySelector(${JSON.stringify(selector)}); node.value=${JSON.stringify(enterprise)}; node.dispatchEvent(new Event('change',{bubbles:true})); })()`);
      await wait(answer ? '!document.querySelector(".scope-refresh").disabled' : '!document.querySelector(".workspace-connection button").disabled');
    }
    assert.equal(await evaluate('document.querySelector("input#enterprise-number,input#answer-enterprise") === null'), true);
  };
  const feedback = text => `document.querySelector('.manager-feedback')?.textContent.includes(${JSON.stringify(text)})`;
  let loggedIn = false;
  const primaryEnterprise = { enterpriseId: '1', enterpriseName: '知序 · 虚构企业', role: 'ADMIN' };
  let accountEnterprises = [primaryEnterprise];
  let csrfSequence = 0;
  let createCalls = 0;
  let mode = 'ok';
  let lastToken = '';
  let stored;
  let held;
  let answerScenario = 'normal';
  const heldAnswers = [];
  let answerCalls = 0;
  let publicCalls = 0;
  let workspaceEnabled = false;
  let holdWorkspace = false;
  let workspaceWrites = 0;
  let liveWorkspaceJob;
  let liveIngestProof;
  let orgVersion = 1;
  let orgSerial = 1000;
  let loseOrgResponse = false;
  const orgDepartments = new Map([['11', { id: '11', name: '<img src=x onerror="window.__orgUnsafe=1">研发', status: 0 }]]);
  const orgMembers = new Map();
  let loseWorkspaceResponse = false;
  let rejectWorkspaceBeforeAccept = false;
  const durableJobs = new Map();
  const durableFeedback = [];
  const fixture = { id: '9007199254740993', name: '研发知识库', permissionVersion: 1 };
  const answerContent = '虚构餐补每日30元，需主管审批。<script>window.__knowledgeUnsafe=1</script>';
  const answerEvidence = { chunkId: '13', documentId: '11', versionId: '12', versionNo: 1, title: '虚构制度',
    chunkIndex: 0, content: answerContent, startOffset: 0, endOffset: answerContent.length, startLine: 1, endLine: 1 };
  const answerFixture = question => {
    const paragraph = { text: '每日30元，需主管审批。', citations: [{ sourceId: 1, evidence: answerEvidence, quote: '虚构餐补每日30元' }] };
    return { mode: 'SYNTHETIC_MODEL', status: 'PARTIAL', message: '虚构资料测试，部分问题缺少依据。', elapsedMs: 80,
      evidence: [answerEvidence], parts: [
        { id: 1, question: '餐补多少？', status: 'ANSWERED', paragraphs: [paragraph], gaps: [] },
        { id: 2, question: '怎么申请？', status: 'PARTIAL', paragraphs: [paragraph], gaps: ['未说明到账时间。'] },
        { id: 3, question: question.includes('打车') ? '打车呢？' : '其他条件？', status: 'NO_EVIDENCE', paragraphs: [], gaps: ['缺少交通制度。'] }
      ] };
  };
  let document;
  let version;
  let uploads = 0;
  const ingestJobs = new Map();
  let ingestWrites = 0;
  let holdIngest = false;
  let loseIngestResponse = false;
  const vectorJobs = new Map();
  let vectorWrites = 0;
  let vectorReads = 0;
  let pointerWrites = 0;
  let vectorScenario = 'normal';
  let pointerScenario = 'normal';
  let vectorPublication = { documentId: '11', jobId: null, revision: '0', status: 'UNPUBLISHED', updatedMs: null, message: '尚未发布向量索引' };
  const page = records => ({ records, total: records.length, pageNum: 1, pageSize: 20, pages: records.length ? 1 : 0 });
  const documentFeedback = text => `document.querySelector('.document-manager > .feedback')?.textContent.includes(${JSON.stringify(text)}) && !document.getElementById('space-number').matches(':disabled')`;
  const clickText = text => evaluate(`(() => {
    const node=[...document.querySelectorAll('.document-manager button')].find(node=>node.textContent.trim()===${JSON.stringify(text)});
    if(!node)throw Error('找不到文档操作按钮');
    const ancestors=[];for(let parent=node.parentElement;parent;parent=parent.parentElement)if(parent.tagName==='DETAILS')ancestors.unshift(parent);
    for(const parent of ancestors)if(!parent.open)parent.querySelector(':scope > summary').click();
    node.scrollIntoView({block:'nearest'});node.click();
  })()`);
  const fulfill = (event, status, data) => cdp('Fetch.fulfillRequest', { requestId: event.requestId, responseCode: status,
    responseHeaders: [{ name: 'Content-Type', value: 'application/json' }], body: Buffer.from(JSON.stringify(data)).toString('base64') });
  let confirmLiveWithdrawal = false;
  let confirmLiveRebuild = false;
  const liveText = liveAnswer
    ? '# 知序虚构企业验收制度（不代表任何真实公司）\n\n加班餐补每人每天上限30元，申请需要直属主管审批。\n\n'
      + '虚构办理说明：加班餐补每人每天上限30元，申请需要直属主管审批。所有金额和审批安排仅供合成测试。\n\n'.repeat(70)
    : '# 知序真实入库联调\n\n' + "固定测试资料：员工可查询制度版本；审批记录含引号'、中文和😀，<资料>按原文保留。\n\n".repeat(70);
  const liveName = `knowledge-live-${Date.now()}.md`;
  const downloadDirectory = join(profile, 'downloads');
  if (live) {
    await mkdir(downloadDirectory);
    await cdp('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: downloadDirectory });
    onEvent = value => {
      if (value.method !== 'Page.javascriptDialogOpening') return;
      const accept = value.params.type === 'confirm' && (
        (confirmLiveWithdrawal && value.params.message.startsWith('确认此操作？撤回或删除'))
        || (confirmLiveRebuild && value.params.message === '仅重建此草稿的片段，不发布。确认继续？')
        || (organizationMode && ['停用部门要求没有关联成员和有效部门授权。确认保存？',
          '直属部门或成员状态变化会影响知识访问；重新启用成员可能恢复其原有明确授权。确认保存？'].includes(value.params.message)));
      confirmLiveWithdrawal = false;
      confirmLiveRebuild = false;
      if (!accept) interceptionErrors.push('真实联调出现非预期确认框，已拒绝');
      cdp('Page.handleJavaScriptDialog', { accept }).catch(error => interceptionErrors.push(error.message));
    };
  }
  if (!live) {
    onEvent = value => {
      if (value.method === 'Page.javascriptDialogOpening') {
        cdp('Page.handleJavaScriptDialog', { accept: true }).catch(error => interceptionErrors.push(error.message));
        return;
      }
      if (value.method !== 'Fetch.requestPaused') return;
      const event = value.params;
      const path = new URL(event.request.url).pathname;
      const respond = async () => {
        if (path === '/api/chat') { publicCalls++; return fulfill(event, 500, { code: 500 }); }
        // 同一会话允许并发写入；读取CSRF不能作废另一在途操作的令牌，登录/退出后才轮换。
        if (path === '/api/auth/csrf') { lastToken ||= `csrf-${++csrfSequence}`; return fulfill(event, 200, { code: 0, data: { headerName: 'X-CSRF-TOKEN', token: lastToken } }); }
        if (event.request.method === 'POST') assert.equal(Object.entries(event.request.headers).find(([key]) => key.toLowerCase() === 'x-csrf-token')?.[1], lastToken);
        if (path === '/api/auth/login') { loggedIn = true; lastToken = ''; return fulfill(event, 200, { code: 0, data: { userId: '25', enterprises: [{ enterpriseId: '1', enterpriseName: '知序 · 虚构企业', role: 'ADMIN' }] } }); }
        if (path === '/api/auth/logout') { loggedIn = false; lastToken = ''; return fulfill(event, 200, { code: 0, data: null }); }
        if (!loggedIn) return fulfill(event, 401, { code: 401, data: null });
        if (path === '/api/auth/me') return fulfill(event, 200, { code: 0, data: { userId: '25', enterprises: accountEnterprises } });
        if (organizationMode && (/\/employees$/.test(path) || path.includes('/organization/'))) {
          const body = event.request.postData ? JSON.parse(event.request.postData) : null;
          const slice = rows => {
            const query = new URL(event.request.url).searchParams;
            const pageNum = Number(query.get('pageNum') ?? 1), pageSize = Number(query.get('pageSize') ?? 20);
            return { records: rows.slice((pageNum - 1) * pageSize, pageNum * pageSize), total: rows.length,
              pageNum, pageSize, pages: Math.ceil(rows.length / pageSize) };
          };
          if (/\/employees$/.test(path)) {
            const id = String(++orgSerial);
            orgMembers.set(id, { id, userId: id, username: body.username, departmentId: null,
              role: 'EMPLOYEE', status: 0, accountStatus: 0, permissionVersion: 1 });
            return fulfill(event, 200, { code: 0, data: { userId: id, memberId: id, enterpriseId: '1' } });
          }
          if (path.endsWith('/members')) return fulfill(event, 200, { code: 0, data: { page: slice([...orgMembers.values()]) } });
          if (path.includes('/members/')) {
            const member = orgMembers.get(path.split('/').at(-1));
            if (!member || member.permissionVersion !== body.expectedVersion) return fulfill(event, 200, { code: 409 });
            Object.assign(member, { departmentId: body.departmentId, status: body.status, permissionVersion: member.permissionVersion + 1 });
            return fulfill(event, 200, { code: 0, data: member });
          }
          if (!body) return fulfill(event, 200, { code: 0, data: { enterprisePermissionVersion: orgVersion, page: slice([...orgDepartments.values()]) } });
          if (body.expectedEnterpriseVersion !== orgVersion) return fulfill(event, 200, { code: 409 });
          const id = path.endsWith('/departments') ? String(++orgSerial) : path.split('/').at(-1);
          if (body.status === 1 && [...orgMembers.values()].some(m => m.departmentId === id)) return fulfill(event, 200, { code: 409 });
          const row = { id, name: body.name, status: body.status ?? 0 }; orgDepartments.set(id, row); orgVersion++;
          if (loseOrgResponse) { loseOrgResponse = false; return cdp('Fetch.failRequest', { requestId: event.requestId, errorReason: 'ConnectionReset' }); }
          return fulfill(event, 200, { code: 0, data: { enterprisePermissionVersion: orgVersion, department: row } });
        }
        if (path.includes('/workspace/')) {
          if (!workspaceEnabled) return fulfill(event, 200, { code: 404 });
          if (path.endsWith('/workspace/chats')) {
            const chats = [...new Map([...durableJobs.values()].map(job => [job.chatId,
              { id: job.chatId, title: job.question.slice(0, 80), createdMs: job.createdMs }])).values()];
            return fulfill(event, 200, { code: 0, data: chats });
          }
          if (path.includes('/workspace/chats/')) return fulfill(event, 200,
            { code: 0, data: [...durableJobs.values()].filter(job => job.chatId === path.split('/').at(-1)).reverse() });
          if (path.endsWith('/workspace/jobs')) {
            workspaceWrites++;
            const body = JSON.parse(event.request.postData);
            assert.deepEqual(Object.keys(body), ['chatId', 'requestId', 'question']);
            if (rejectWorkspaceBeforeAccept) {
              rejectWorkspaceBeforeAccept = false;
              return cdp('Fetch.failRequest', { requestId: event.requestId, errorReason: 'ConnectionReset' });
            }
            if (!durableJobs.has(body.requestId)) {
              const createdMs = Date.now();
              durableJobs.set(body.requestId, { id: body.requestId, chatId: body.chatId,
                question: body.question, status: 'PENDING', createdMs, deadlineMs: createdMs + 30000,
                finishedMs: null, answer: null, message: '已受理，可查询原任务。' });
            }
            if (loseWorkspaceResponse) {
              loseWorkspaceResponse = false;
              return cdp('Fetch.failRequest', { requestId: event.requestId, errorReason: 'ConnectionReset' });
            }
            return fulfill(event, 200, { code: 0, data: durableJobs.get(body.requestId) });
          }
          if (path.includes('/workspace/jobs/')) {
            const id = path.endsWith('/cancel') ? path.split('/').at(-2) : path.split('/').at(-1);
            const job = durableJobs.get(id);
            if (!job) return fulfill(event, 200, { code: 404 });
            if (path.endsWith('/cancel')) { job.status = 'CANCELLED'; job.message = '任务已取消'; job.finishedMs = Date.now(); }
            else if (!holdWorkspace && job.status === 'PENDING') {
              job.status = 'COMPLETED'; job.answer = answerFixture(job.question); job.finishedMs = Date.now(); job.message = '处理完成';
            }
            return fulfill(event, 200, { code: 0, data: job });
          }
          if (path.endsWith('/workspace/feedback')) {
            if (event.request.method === 'POST') {
              const body = JSON.parse(event.request.postData);
              let old = durableFeedback.find(item => item.id === body.id);
              if (!old) { old = { id: body.id, jobId: body.jobId, chunkId: body.chunkId, category: body.category,
                commentText: body.comment, status: 'OPEN', resolution: null, resolvedBy: null, revision: 1,
                createdMs: Date.now(), question: durableJobs.get(body.jobId).question }; durableFeedback.push(old); }
              return fulfill(event, 200, { code: 0, data: old });
            }
            return fulfill(event, 200, { code: 0, data: durableFeedback });
          }
          if (path.endsWith('/resolve')) {
            const entry = durableFeedback.find(item => item.id === path.split('/').at(-2));
            const body = JSON.parse(event.request.postData);
            assert.equal(body.revision, entry.revision); entry.status = body.status; entry.resolution = body.resolution;
            entry.resolvedBy = '25'; entry.revision++;
            return fulfill(event, 200, { code: 0, data: null });
          }
          return fulfill(event, 200, { code: 404 });
        }
        if (path.endsWith('/answers/mode')) return fulfill(event, 200, { code: 0, data: 'SYNTHETIC_MODEL' });
        if (path.endsWith('/answers/retrieval-mode')) return fulfill(event, 200, { code: 0, data: 'HYBRID' });
        if (path.endsWith('/answers')) {
          answerCalls++;
          assert.deepEqual(Object.keys(JSON.parse(event.request.postData)), ['question']);
          if (answerScenario === 'hold') { heldAnswers.push(event); return; }
          if (answerScenario === 'busy') return fulfill(event, 503, { code: 503, message: 'SECRET_UPSTREAM' });
          const data = answerFixture(JSON.parse(event.request.postData).question);
          if (answerScenario === 'unavailable') {
            data.status = 'MODEL_UNAVAILABLE';
            data.message = '模型未完成，未自动重试。';
            data.parts = [{ id: 1, question: '餐补？', status: 'MODEL_UNAVAILABLE', paragraphs: [], gaps: ['模型未通过校验。'] }];
          }
          if (answerScenario.startsWith('scope-')) {
            data.evidence = [];
            data.status = answerScenario === 'scope-unknown' ? 'SCOPE_UNKNOWN' : 'SCOPE_UNAVAILABLE';
            data.message = '学校业务范围尚未确认，本次不作制度判断。';
            data.parts = [{ id: 1, question: JSON.parse(event.request.postData).question, status: data.status,
              paragraphs: [], gaps: ['未判断业务归属，未自动重试或转公开搜索。'] }];
          }
          if (answerScenario === 'school-mixed') {
            data.parts[1] = { id: 2, question: '宠物保险呢？', status: 'OUT_OF_SCOPE', paragraphs: [], gaps: ['超出学校业务范围。'] };
            data.parts[2] = { id: 3, question: '多久到账？', status: 'NO_EVIDENCE', paragraphs: [], gaps: ['未提供到账日期。'] };
          }
          return fulfill(event, 200, { code: 0, data });
        }
        if (path.includes('/answers/evidence/')) return fulfill(event, 200,
          answerScenario === 'revoked' ? { code: 404, message: 'SECRET_STORAGE_PATH' } : { code: 0, data: answerEvidence });
        if (mode === 'denied') return fulfill(event, 200, { code: 404, message: 'SECRET_SQL_TOKEN' });
        if (mode === 'expired') { loggedIn = false; return fulfill(event, 401, { code: 401 }); }
        if (path.includes('/vector-jobs')) {
          if (path.endsWith('/vector-jobs') && event.request.method === 'POST') {
            const body = JSON.parse(event.request.postData);
            assert.deepEqual(Object.keys(body), ['requestId', 'expectedRevision']);
            assert.equal(body.expectedRevision, String(document.revision));
            vectorWrites++;
            const now = Date.now();
            const job = { id: body.requestId, documentId: document.id, versionId: version.id, expectedRevision: body.expectedRevision,
              status: 'QUEUED', expectedChunks: 1, verifiedChunks: 0, createdMs: now, deadlineMs: now + 120000,
              finishedMs: null, failureCode: null, message: '向量任务已受理' };
            vectorJobs.set(job.id, job);
            if (vectorScenario === 'lost') return fulfill(event, 502, { code: 502, message: 'SECRET_VECTOR_KEY' });
            return fulfill(event, 200, { code: 0, data: job });
          }
          const pieces = path.split('/'); const cancel = pieces.at(-1) === 'cancel';
          const job = vectorJobs.get(cancel ? pieces.at(-2) : pieces.at(-1));
          if (!cancel) vectorReads++;
          if (vectorScenario === 'revoked') return fulfill(event, 200, { code: 404, message: 'SECRET_VECTOR_KEY' });
          if (vectorScenario === 'unavailable') return fulfill(event, 503, { code: 503, message: 'SECRET_VECTOR_KEY' });
          if (!job) return fulfill(event, 200, { code: 404 });
          if (cancel && ['QUEUED', 'RUNNING'].includes(job.status)) Object.assign(job, {
            status: 'CANCELLED', finishedMs: String(Date.now()), message: '向量任务已取消'
          });
          if (!cancel && vectorScenario === 'normal' && ['QUEUED', 'RUNNING'].includes(job.status)) Object.assign(job, {
            status: 'VERIFIED', verifiedChunks: 1, finishedMs: String(Date.now()), message: '<img src=x onerror="window.__vectorUnsafe=1">向量核验完成，等待显式发布'
          });
          return fulfill(event, 200, { code: 0, data: job });
        }
        if (path.includes('/vector-publication')) {
          if (event.request.method === 'POST') {
            pointerWrites++;
            const body = JSON.parse(event.request.postData);
            assert.equal(body.expectedRevision, vectorPublication.revision);
            if (pointerScenario === 'conflict') {
              vectorPublication = { ...vectorPublication, revision: (BigInt(vectorPublication.revision) + 1n).toString(),
                status: 'WITHDRAWN', jobId: null, updatedMs: String(Date.now()), message: '其他维护者已更新指针' };
              return fulfill(event, 409, { code: 409, message: 'SECRET_VECTOR_KEY' });
            }
            const publish = path.endsWith('/publish');
            if (publish) assert.equal(vectorJobs.get(body.jobId)?.status, 'VERIFIED');
            else assert.equal(body.expectedJobId, vectorPublication.jobId);
            if (pointerScenario !== 'uncommitted') vectorPublication = { ...vectorPublication,
              revision: (BigInt(vectorPublication.revision) + 1n).toString(), status: publish ? 'READY' : 'WITHDRAWN',
              jobId: publish ? body.jobId : null, updatedMs: String(Date.now()), message: publish ? '向量索引已发布' : '向量索引已撤回' };
            if (['lost', 'uncommitted'].includes(pointerScenario)) return fulfill(event, 502, { code: 502 });
          }
          return fulfill(event, 200, { code: 0, data: vectorPublication });
        }
        if (path.endsWith('/grants')) return fulfill(event, 200, mode === 'reader' ? { code: 404 } : { code: 0, data: [
          { id: '1', memberId: '3', departmentId: null, permission: 'MAINTAIN', status: 0 }
        ] });
        if (path.endsWith('/documents') && event.request.method === 'POST') {
          uploads++;
          assert.match(event.request.headers['Content-Type'] ?? event.request.headers['content-type'], /^multipart\/form-data; boundary=/);
          document = { id: '11', knowledgeSpaceId: fixture.id, title: '研发制度', ownerMemberId: '3', revision: 1, publishedVersionId: null };
          version = { id: '12', documentId: '11', versionNo: 1, originalName: '研发制度.md', fileSize: 14,
            parseStatus: 'PENDING', publicationStatus: 'DRAFT', chunkCount: 0, failureCode: null, validFromMs: null, validUntilMs: null };
          return fulfill(event, 200, { code: 0, data: version });
        }
        if (path.includes('/ingest-jobs')) {
          if (path.endsWith('/ingest-jobs') && event.request.method === 'POST') {
            const body = JSON.parse(event.request.postData);
            assert.equal(body.expectedRevision, document.revision);
            ingestWrites++;
            const now = Date.now();
            const job = { id: body.requestId, documentId: document.id, versionId: version.id, expectedRevision: body.expectedRevision,
              action: body.action, status: 'QUEUED', attempts: 0, createdMs: now, deadlineMs: now + 30000,
              finishedMs: null, failureCode: null, message: '已受理，等待本机解析' };
            ingestJobs.set(job.id, job);
            if (loseIngestResponse) { loseIngestResponse = false; return fulfill(event, 502, { code: 502 }); }
            return fulfill(event, 200, { code: 0, data: job });
          }
          if (path.endsWith('/ingest-jobs')) {
            const tasks = [...ingestJobs.values()].reverse();
            if (!holdIngest) for (const job of tasks) if (['QUEUED', 'RUNNING'].includes(job.status)) {
              document.revision++; version = { ...version, parseStatus: 'READY', chunkCount: 1 };
              Object.assign(job, { status: 'SUCCEEDED', attempts: 1, finishedMs: String(Date.now()), message: '片段入库完成，请检查后发布' });
            }
            return fulfill(event, 200, { code: 0, data: tasks.slice(0, 10) });
          }
          const pieces = path.split('/'); const id = pieces.at(-1) === 'cancel' ? pieces.at(-2) : pieces.at(-1);
          const job = ingestJobs.get(id);
          if (!job) return fulfill(event, 200, { code: 404 });
          if (pieces.at(-1) === 'cancel' && ['QUEUED', 'RUNNING'].includes(job.status))
            Object.assign(job, { status: 'CANCELLED', finishedMs: String(Date.now()), message: '任务已取消' });
          return fulfill(event, 200, { code: 0, data: job });
        }
        if (path.endsWith('/parse')) {
          assert.equal(JSON.parse(event.request.postData).expectedRevision, document.revision);
          document.revision++;
          version = { ...version, parseStatus: 'READY', chunkCount: 1 };
          return fulfill(event, 200, { code: 0, data: version });
        }
        if (path.endsWith('/lifecycle')) {
          const body = JSON.parse(event.request.postData);
          assert.equal(body.expectedRevision, document.revision);
          document.revision++;
          document.publishedVersionId = body.action === 'PUBLISH' ? version.id : null;
          version.publicationStatus = body.action === 'PUBLISH' ? 'PUBLISHED' : 'WITHDRAWN';
          return fulfill(event, 200, { code: 0, data: document });
        }
        if (path.endsWith('/documents')) return fulfill(event, 200, { code: 0, data: page(document ? [document] : []) });
        if (path.endsWith('/versions')) return fulfill(event, 200, { code: 0, data: page([version]) });
        if (path.endsWith('/chunks')) return fulfill(event, 200, { code: 0, data: page([
          { id: '13', chunkIndex: 0, content: '<script>不可执行</script>制度正文', startOffset: 0, endOffset: 26, startLine: 1, endLine: 1 }
        ].map(value => ({ ...value, endOffset: value.content.length }))) });
        if (path.endsWith('/documents/11')) return fulfill(event, 200, { code: 0, data: document });
        if (event.request.method === 'POST' && /\/knowledge-spaces$/.test(path)) {
          createCalls++;
          stored = { ...fixture, name: JSON.parse(event.request.postData).name };
          if (mode === 'hold') { held = event; return; }
          return fulfill(event, 200, { code: 0, data: stored });
        }
        if (/\/knowledge-spaces$/.test(path)) return fulfill(event, 200, { code: 0, data: page([stored ?? fixture]) });
        return fulfill(event, 200, { code: 0, data: stored ?? fixture });
      };
      respond().catch(error => interceptionErrors.push(error.message));
    };
    await cdp('Fetch.enable', { patterns: [{ urlPattern: '*/api/auth/*' }, { urlPattern: '*/api/enterprises/*' }, { urlPattern: '*/api/chat*' }] });
  }
  await cdp('Page.enable');
  await cdp('Runtime.enable');
  await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
  await cdp('Page.navigate', { url: base });
  await wait('document.querySelector(".primary-nav") !== null');
  await navigateKnowledge();
  await wait('document.getElementById("enterprise-password") && !document.getElementById("enterprise-password").matches(":disabled")');
  if (!live && !organizationMode && process.env.UI_SCREENSHOT_DIR) {
    await mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
    const { data } = await cdp('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-login.png'), Buffer.from(data, 'base64'));
  }
  if (live) {
    console.log('真实联调已打开独立浏览器。请在该窗口使用已有企业账号登录；无需向AI提供密码。5分钟内等待登录，不读取输入框。');
    await wait('document.querySelector(".account-line") !== null', 3000);
  } else {
    await fill('enterprise-username', 'fictitious-admin');
    await fill('enterprise-password', 'not-a-real-password');
    await submit('企业账号登录');
    await wait('document.querySelector(".account-line") !== null');
  }
  passed('登录页面经过Session/CSRF流程显示当前账号');
  await chooseWorkspace( process.env.ENTERPRISE_UI_ID ?? '1');
  if (!live && !organizationMode) {
    assert.equal(await evaluate('document.querySelector("input#enterprise-number") === null && document.querySelectorAll(".space-cards li").length === 1'), true);
    assert.ok((await evaluate('document.querySelector(".account-identity").textContent')).includes(primaryEnterprise.enterpriseName));
    assert.equal(await evaluate('document.querySelector(".new-chat") === null && document.querySelector(".history-list") === null'), true);
    passed('单企业登录后自动加载授权空间，无企业编号输入，导航无重复入口或联网会话串页');
    accountEnterprises = [];
    await evaluate('[...document.querySelectorAll(".primary-nav button")].find(n=>n.textContent.includes("企业问答")).click()');
    await wait('document.querySelector(".answer-notice")?.textContent.includes("尚未分配企业")');
    assert.equal(await evaluate('document.getElementById("knowledge-question").disabled'), true);
    passed('未分配企业时显示联系管理员提示，禁止提问且不猜测默认企业');
    accountEnterprises = [primaryEnterprise, { enterpriseId: '2', enterpriseName: '<img src=x onerror="window.__enterpriseUnsafe=1">乙企业', role: 'EMPLOYEE' }];
    await navigateKnowledge();
    await wait('document.querySelectorAll("#enterprise-workspace option").length === 3 && !document.querySelector(".account-line > button").disabled');
    assert.equal(await evaluate('document.getElementById("enterprise-workspace").value'), '');
    assert.equal(await evaluate('document.querySelector(".space-cards") === null && window.__enterpriseUnsafe === undefined'), true);
    await chooseWorkspace('1');
    await wait('document.querySelector(".space-cards li") !== null');
    passed('多企业只按所属名称选择，未选择不加载空间，企业名称HTML保持纯文本');
    accountEnterprises = undefined;
    await evaluate('[...document.querySelectorAll(".primary-nav button")].find(n=>n.textContent.includes("企业问答")).click()');
    await wait('document.querySelector(".answer-notice")?.textContent.includes("重启最新版后端")');
    assert.equal(await evaluate('document.getElementById("knowledge-question").disabled && document.querySelector("input#answer-enterprise") === null'), true);
    passed('旧后端缺少企业归属时明确提示升级，不回退为手填编号或硬编码企业');
    accountEnterprises = [primaryEnterprise];
    await navigateKnowledge();
    await chooseWorkspace('1');
  }
  if (liveOffice) {
    const proof = await checkOfficeLive({ evaluate, passed, enterprise: process.env.ENTERPRISE_UI_ID ?? '1', permissions: liveOfficePermissions, setCleanup: work => { cleanupLive = work; } });
    assert.deepEqual(exceptions, []);
    if (process.env.UI_SCREENSHOT_DIR) {
      await mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
      await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-office-live-result.json'), JSON.stringify({ ...proof, checks }, null, 2) + '\n', 'utf8');
    }
    console.log(`文件与本人改密真实联调完成：${checks}/${checks}；测试版本已撤回、测试成员已停用，无模型/搜索调用。`);
  } else if (organizationMode) {
    const proof = await checkOrganizationUi({ evaluate, wait, passed, live, setCleanup: work => { cleanupLive = work; },
      injectConflict: id => { orgMembers.get(id).permissionVersion++; }, loseResponse: () => { loseOrgResponse = true; } });
    cleanupLive = undefined;
    for (const width of [320, 390, 768, 1440]) {
      await cdp('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: width < 760 });
      await delay(250);
      assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true, `${width}px组织页溢出`);
    }
    passed('组织面板展开后320/390/768/1440像素无水平溢出');
    if (process.env.UI_SCREENSHOT_DIR) {
      await mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
      await evaluate('document.querySelector(".organization-panel").scrollIntoView({block:"start"})');
      const screenshot = await cdp('Page.captureScreenshot', { format: 'png' });
      await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'organization.png'), Buffer.from(screenshot.data, 'base64'));
    }
    await evaluate('document.querySelector(".account-line button").click()'); await wait(feedback('已退出登录'));
    assert.equal(await evaluate('document.querySelector(".organization-panel")===null'), true);
    assert.equal((await evaluate('Object.keys(localStorage).concat(Object.keys(sessionStorage))')).some(key => /token|password|session|account|organization/i.test(key)), false);
    passed('退出后清除组织私有展示，不将账号口令或组织记录写入浏览器存储');
    assert.deepEqual(exceptions, []); assert.deepEqual(interceptionErrors, []);
    if (process.env.UI_SCREENSHOT_DIR) await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'organization-result.json'),
      JSON.stringify({ ...proof, checkedAt: new Date().toISOString(), checks, mode: live ? '真实MySQL' : '浏览器拦截' }, null, 2) + '\n', 'utf8');
    console.log(`组织页面验收完成：${checks}/${checks}；模式=${live ? '真实MySQL' : '浏览器拦截'}；测试员工与部门最终停用，无模型/搜索调用。`);
  } else {
  let testName = live ? `联调测试空间-${new Date().toISOString().replaceAll(':', '-')}` : '<img src=x onerror="window.__unsafe=true">研发知识库';
  if (!live) {
    await evaluate('document.querySelector(".workspace-connection button").click()');
    await wait('document.querySelector(".space-cards li button") && !document.querySelector(".workspace-connection button").disabled');
    assert.equal(await evaluate('document.querySelectorAll(".space-cards li").length'), 1);
    await evaluate(`(() => { const node=document.querySelector('input[aria-label="筛选本页知识空间"]');node.value='不匹配的筛选条件';node.dispatchEvent(new Event('input',{bubbles:true})); })()`);
    await wait('document.querySelector(".directory-empty h4")?.textContent.includes("没有匹配")');
    assert.equal(await evaluate('document.querySelectorAll(".space-cards li").length'), 0);
    await evaluate(`(() => { const node=document.querySelector('input[aria-label="筛选本页知识空间"]');node.value='';node.dispatchEvent(new Event('input',{bubbles:true})); })()`);
    await wait('document.querySelector(".space-cards li button")');
    for (const width of [320, 390, 768, 1440]) {
      await cdp('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: width < 760 });
      await delay(250);
      assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth && document.querySelector(".knowledge-manager").scrollWidth <= document.querySelector(".knowledge-manager").clientWidth'), true, `${width}px空间目录溢出`);
      if (process.env.UI_SCREENSHOT_DIR && [390, 1440].includes(width)) {
        await evaluate('document.querySelector(".library-heading").scrollIntoView({block:"start"})');
        const { data } = await cdp('Page.captureScreenshot', { format: 'png' });
        await writeFile(join(process.env.UI_SCREENSHOT_DIR, `knowledge-directory-${width}.png`), Buffer.from(data, 'base64'));
      }
    }
    await evaluate('document.querySelector(".space-cards li button").click()');
    await wait('document.querySelector(".space-result") && !document.querySelector(".workspace-connection button").disabled');
    assert.equal(await evaluate('getComputedStyle(document.querySelector(".manager-grid")).display'), 'none');
    await evaluate('document.querySelector(".back-to-spaces").click()');
    await wait('document.querySelector(".space-result") === null && document.querySelector(".space-cards li button") && !document.querySelector(".workspace-connection button").disabled');
    await cdp('Page.bringToFront');
    await evaluate('document.querySelector(".manager-grid details > summary").focus()');
    assert.equal(await evaluate('document.activeElement.matches(".manager-grid details > summary")'), true);
    await cdp('Input.dispatchKeyEvent', { type: 'keyDown', key: ' ', code: 'Space', windowsVirtualKeyCode: 32, text: ' ' });
    await cdp('Input.dispatchKeyEvent', { type: 'keyUp', key: ' ', code: 'Space', windowsVirtualKeyCode: 32 });
    await wait('document.querySelector(".manager-grid details").open');
    assert.equal(await evaluate('document.querySelector(".manager-grid details").open'), true);
    assert.equal(createCalls, 0);
    passed('空间卡片、当前页筛选/清空、进入与返回目录、键盘可用折叠入口及四种宽度通过，无额外创建');
  }
  if (liveAnswer) {
    await fill('space-number', acceptanceSpace);
    await submit('读取知识空间');
    await wait('document.querySelector(".space-result") !== null');
    testName = await evaluate('document.querySelector(".space-result h3").textContent');
    const preflight = await evaluate(`(async () => {
      const root='/api/enterprises/${process.env.ENTERPRISE_UI_ID ?? '1'}/knowledge-spaces/${acceptanceSpace}';
      const mode=await (await fetch(root+'/answers/mode',{cache:'no-store'})).json();
      const docs=await (await fetch(root+'/documents?pageNum=1&pageSize=500',{cache:'no-store'})).json();
      return {mode:mode.code===0?mode.data:'UNAVAILABLE', total:docs.data?.total,
        published:docs.data?.records.filter(item=>item.publishedVersionId!==null).length};
    })()`);
    assert.equal(preflight.mode, 'SYNTHETIC_MODEL', '未检测到新版后端或该专用虚构空间未获模型许可，禁止继续外发');
    assert.ok(Number.isInteger(preflight.total) && preflight.total <= 500 && preflight.published === 0,
      '专用测试空间已有发布资料或目录过大，停止验收以免混入其他原文');
  } else {
    await fill('space-name', testName);
    if (!live) mode = 'hold';
    await submit('创建知识空间');
  }
  if (!live) {
    await wait('document.querySelector(".manager-feedback").textContent.includes("正在创建")');
    await submit('创建知识空间');
    await delay(150);
    assert.equal(createCalls, 1);
    assert.ok(held);
    await fulfill(held, 200, { code: 0, data: stored });
    mode = 'ok';
  }
  await wait('document.querySelector(".space-result") !== null');
  const createdId = await evaluate('document.getElementById("space-number").value');
  assert.match(createdId, /^[1-9]\d*$/);
  if (liveWorkspace) {
    // 写入测试文档前先核对工作区，缺表或旧后端时立即停止，避免无效上传和发布。
    const probe = await evaluate(`(async()=>{
      const r=await fetch('/api/enterprises/${process.env.ENTERPRISE_UI_ID ?? '1'}/knowledge-spaces/${createdId}/workspace/chats',{cache:'no-store'});
      let code;try{code=(await r.json()).code;}catch{}
      return {httpStatus:r.status,code};
    })()`);
    assert.ok(probe.httpStatus === 200 && probe.code === 0,
      `工作区预检失败：HTTP=${probe.httpStatus}，code=${probe.code}；请核对IDEA异常及20261019_01三表迁移，不重放全库schema。`);
  }
  assert.equal(await evaluate('document.querySelector(".space-result h3").textContent'), testName);
  assert.equal(await evaluate('document.querySelectorAll(".space-result img").length'), 0);
  passed(liveAnswer ? '专用虚构空间许可已核对，当前没有其他发布资料' : '创建空间成功，自动填入字符串编号；防重复点击，名称按纯文本显示');
  await wait('!document.getElementById("space-number").matches(":disabled")');
  await submit('读取知识空间');
  await wait(feedback('读取成功'));
  await wait('!document.getElementById("space-number").matches(":disabled")');
  passed('通过页面代理重新读取已提交空间，结果与创建一致');
  await reload();
  await wait('document.querySelector(".primary-nav") !== null');
  await navigateKnowledge();
  await wait('document.querySelector(".account-line") !== null');
  await chooseWorkspace( process.env.ENTERPRISE_UI_ID ?? '1');
  await fill('space-number', createdId);
  await submit('读取知识空间');
  await wait(feedback('读取成功'));
  await wait('!document.getElementById("space-number").matches(":disabled")');
  passed('刷新后由Cookie恢复会话，重新授权读取持久化记录');
  if (!live) {
    assert.equal(await evaluate('document.querySelector(".upload-panel").open'), true);
    await evaluate(`(() => { const transfer=new DataTransfer(); transfer.items.add(new File(['研发制度正文'], '研发制度.md', {type:'text/markdown'})); const input=document.querySelector('.document-manager input[type=file]'); input.files=transfer.files; input.dispatchEvent(new Event('change',{bubbles:true})); })()`);
    await submit('上传知识文档');
    await wait(documentFeedback('原件与草稿版本已保存'));
    assert.equal(await evaluate('document.querySelector(".upload-panel").open'), false);
    await evaluate(`(() => { const node=document.querySelector('input[aria-label="筛选本页文档"]');node.value='不匹配的文档';node.dispatchEvent(new Event('input',{bubbles:true})); })()`);
    await wait('document.querySelector(".documents-empty h4")?.textContent.includes("没有匹配")');
    await evaluate(`(() => { const node=document.querySelector('input[aria-label="筛选本页文档"]');node.value='';node.dispatchEvent(new Event('input',{bubbles:true})); })()`);
    await wait('document.querySelector(".document-list button")');
    assert.equal(await evaluate('document.querySelector(".version-guidance").textContent.includes("下一步")'), true);
    passed('空目录展开上传，保存后收起；文档当前页筛选可恢复，草稿显示明确下一步');
    assert.equal(uploads, 1);
    assert.equal(await evaluate('document.querySelector(".version-list").textContent.includes("待解析 / 草稿")'), true);
    passed('页面通过multipart保存原件与草稿，展示版本及修订号');
    await clickText('解析并入库');
    await wait(documentFeedback('片段入库完成'));
    assert.equal(ingestWrites, 1);
    holdIngest = true; loseIngestResponse = true;
    await clickText('重建草稿片段');
    await wait(documentFeedback('结果未知'));
    assert.equal(ingestWrites, 2);
    await clickText('核对原任务，必要时同编号重传');
    await wait(documentFeedback('已受理'));
    assert.equal(ingestWrites, 2);
    await clickText('取消入库任务');
    await wait(documentFeedback('任务已取消'));
    passed('入库提交响应丢失后读取原UUID，不重复提交；取消后明确显示终态');
    await clickText('重建草稿片段');
    await wait(documentFeedback('已受理'));
    assert.equal(ingestWrites, 3);
    await reload(); await wait('document.querySelector(".primary-nav") !== null');
    await navigateKnowledge(); await wait('document.querySelector(".account-line") !== null');
    await chooseWorkspace( '1'); await fill('space-number', createdId); await submit('读取知识空间');
    await wait(feedback('读取成功')); await wait('document.querySelector(".document-list button") && !document.getElementById("space-number").matches(":disabled")');
    await evaluate('document.querySelector(".document-list button").click()');
    await wait(documentFeedback('已读取文档'));
    await clickText('查看入库任务'); await wait(documentFeedback('已受理'));
    assert.equal(ingestWrites, 3);
    await clickText('取消入库任务'); await wait(documentFeedback('任务已取消'));
    passed('页面刷新后选择原版本恢复持久入库状态，未重新提交或调用同步解析');
    holdIngest = false;
    await clickText('重建草稿片段'); await wait(documentFeedback('片段入库完成'));
    assert.equal(ingestWrites, 4);
    passed('仅草稿可显式重建，成功后刷新文档修订与片段，仍需手动发布');
    await clickText('查看片段');
    await wait(documentFeedback('片段已读取'));
    assert.equal(await evaluate('document.querySelector(".document-manager pre").textContent'), '<script>不可执行</script>制度正文');
    assert.equal(await evaluate('document.querySelector(".document-manager pre script") === null'), true);
    await clickText('发布此版本');
    await wait(documentFeedback('生命周期状态已更新'));
    assert.equal(await evaluate('document.querySelector(".version-list").textContent.includes("已发布")'), true);
    passed('解析、片段预览及明确发布携带当前修订号；原文按纯文本显示');
    await clickText('管理版本 1 的索引'); await wait(documentFeedback('尚未发布向量索引'));
    await clickText('构建当前发布版本索引'); await wait(documentFeedback('向量任务已受理'));
    const firstVectorId = await evaluate('document.getElementById("vector-task-id").value');
    assert.equal(vectorWrites, 1);
    assert.equal(await evaluate('[...document.querySelectorAll("button")].find(n=>n.textContent.trim()==="发布已核验索引").disabled'), true);
    await wait(documentFeedback('向量核验完成'));
    assert.equal(pointerWrites, 0);
    assert.equal(await evaluate('document.querySelector(".vector-job img") === null && window.__vectorUnsafe === undefined'), true);
    passed('向量任务自动轮询至VERIFIED；不自动发布，服务端文案按纯文本显示');
    pointerScenario = 'lost';
    await clickText('发布已核验索引'); await wait(documentFeedback('结果未知'));
    await clickText('刷新资料与权限'); await wait(documentFeedback('资料状态已刷新'));
    assert.equal(await evaluate('[...document.querySelectorAll("button")].find(n=>n.textContent.trim()==="发布已核验索引").disabled'), true);
    await clickText('查询当前索引指针'); await wait(documentFeedback('向量索引已发布'));
    assert.equal(pointerWrites, 1);
    assert.equal(await evaluate('document.querySelector(".vector-notice").textContent.includes("已核对")'), true);
    passed('发布回执丢失后刷新目录不能解锁；读取已提交指针核对，无重复写入');
    pointerScenario = 'normal';
    await clickText('撤回索引'); await wait(documentFeedback('向量索引已撤回'));
    assert.equal(document.publishedVersionId, version.id);
    assert.equal(await evaluate('document.querySelector(".version-list").textContent.includes("已发布")'), true);
    passed('索引撤回使用独立指针修订，不撤回文档版本');
    pointerScenario = 'conflict';
    await clickText('发布已核验索引'); await wait('document.querySelector(".document-manager > .feedback").classList.contains("error") && !document.getElementById("space-number").matches(":disabled")');
    assert.equal(await evaluate('document.querySelector(".vector-publication") === null'), true);
    await clickText('查询当前索引指针'); await wait(documentFeedback('其他维护者已更新指针'));
    assert.equal(pointerWrites, 3);
    passed('CAS冲突丢弃旧指针，人工刷新后才允许按新修订操作');
    pointerScenario = 'uncommitted';
    await clickText('发布已核验索引'); await wait(documentFeedback('结果未知'));
    await clickText('查询当前索引指针'); await wait(documentFeedback('其他维护者已更新指针'));
    assert.equal(await evaluate('document.querySelector(".vector-notice").textContent.includes("尚未确认")'), true);
    assert.equal(await evaluate('[...document.querySelectorAll("button")].find(n=>n.textContent.trim()==="发布已核验索引").disabled'), true);
    assert.equal(pointerWrites, 4);
    // 模拟原请求稍后落库；先读到旧指针绝不当成未执行而重发。
    vectorPublication = { ...vectorPublication, revision: (BigInt(vectorPublication.revision) + 1n).toString(),
      jobId: firstVectorId, status: 'READY', updatedMs: String(Date.now()), message: '向量索引已发布' };
    await clickText('查询当前索引指针'); await wait(documentFeedback('向量索引已发布'));
    pointerScenario = 'normal';
    passed('未知发布读到旧指针继续保持待核对，迟到提交可用同指针查询恢复');
    vectorScenario = 'lost';
    await clickText('构建当前发布版本索引'); await wait(documentFeedback('结果未知'));
    const recoveryId = await evaluate('document.getElementById("vector-task-id").value');
    assert.notEqual(recoveryId, firstVectorId);
    assert.equal(vectorWrites, 2);
    assert.equal(await evaluate('document.body.textContent.includes("SECRET_VECTOR_KEY")'), false);
    vectorScenario = 'hold';
    const savedRevision = vectorJobs.get(recoveryId).expectedRevision;
    vectorJobs.get(recoveryId).expectedRevision = '999';
    await clickText('查询原向量任务'); await wait(documentFeedback('原任务修订与提交参数不一致'));
    assert.equal(await evaluate('[...document.querySelectorAll("button")].find(n=>n.textContent.trim()==="构建当前发布版本索引").disabled'), true);
    vectorJobs.get(recoveryId).expectedRevision = savedRevision;
    await clickText('查询原向量任务'); await wait(documentFeedback('向量任务已受理'));
    assert.equal(vectorWrites, 2);
    await clickText('停止自动查询');
    const beforeStop = vectorReads;
    await delay(1800); assert.equal(vectorReads, beforeStop);
    await clickText('取消向量任务'); await wait(documentFeedback('向量任务已取消'));
    passed('提交回执丢失只查原UUID；停止查询不取消后台任务，显式取消保留真实终态');
    await reload(); await wait('document.querySelector(".primary-nav") !== null');
    await navigateKnowledge(); await wait('document.querySelector(".account-line") !== null');
    await chooseWorkspace( '1'); await fill('space-number', createdId); await submit('读取知识空间');
    await wait('document.querySelector(".document-list button") && !document.getElementById("space-number").matches(":disabled")');
    await evaluate('document.querySelector(".document-list button").click()'); await wait(documentFeedback('已读取文档'));
    await clickText('管理版本 1 的索引'); await wait(documentFeedback('向量索引已发布'));
    assert.equal(await evaluate('document.getElementById("vector-task-id").value'), '');
    await fill('vector-task-id', '../invalid');
    assert.equal(await evaluate('[...document.querySelectorAll("button")].find(n=>n.textContent.trim()==="查询原向量任务").disabled'), true);
    await fill('vector-task-id', recoveryId);
    await clickText('查询原向量任务'); await wait(documentFeedback('向量任务已取消'));
    assert.equal(vectorWrites, 2);
    passed('刷新后输入原编号恢复本人任务，不持久化编号、不自动提交');
    await clickText('构建当前发布版本索引'); await wait(documentFeedback('向量任务已受理'));
    const beforeLimit = vectorReads;
    // 只推进浏览器时钟，模拟到达自动查询上限；服务端任务截止保持原值。
    await evaluate('window.__vectorClock = Date.now; Date.now = () => window.__vectorClock() + 130001');
    await wait('document.querySelector(".vector-notice")?.textContent.includes("自动查询已到上限")');
    assert.equal(vectorReads, beforeLimit);
    await evaluate('Date.now = window.__vectorClock; delete window.__vectorClock');
    await clickText('取消向量任务'); await wait(documentFeedback('向量任务已取消'));
    passed('自动查询达到时间上限即停止，不多发一次请求，不把后台任务伪装成超时终态');
    if (process.env.UI_SCREENSHOT_DIR) {
      await evaluate('document.querySelector("section[aria-label=向量索引管理]").scrollIntoView()');
      await mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
      const { data } = await cdp('Page.captureScreenshot', { format: 'png' });
      await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-vector.png'), Buffer.from(data, 'base64'));
    }
    vectorScenario = 'hold';
    await clickText('构建当前发布版本索引'); await wait(documentFeedback('向量任务已受理'));
    vectorScenario = 'unavailable';
    await wait('document.querySelector(".document-manager > .feedback").classList.contains("error") && !document.getElementById("space-number").matches(":disabled")');
    const beforeFailure = vectorReads;
    await delay(1800); assert.equal(vectorReads, beforeFailure);
    assert.equal(await evaluate('document.body.textContent.includes("SECRET_VECTOR_KEY")'), false);
    vectorScenario = 'hold';
    await clickText('查询原向量任务'); await wait(documentFeedback('向量任务已受理'));
    await clickText('取消向量任务'); await wait(documentFeedback('向量任务已取消'));
    passed('查询故障立即停止轮询并反馈，人工查询恢复，不无限重试或显示上游秘密');
    vectorScenario = 'revoked';
    await clickText('查询原向量任务'); await wait(documentFeedback('不存在或不可访问'));
    assert.equal(await evaluate('document.querySelector(".selected-document") === null'), true);
    vectorScenario = 'normal';
    await clickText('刷新资料与权限'); await wait(documentFeedback('资料状态已刷新'));
    await evaluate('document.querySelector(".document-list button").click()'); await wait(documentFeedback('已读取文档'));
    passed('任务不可访问清除文档及索引私有展示，不以旧任务绕过撤权');
    // 截图和窄屏验证保持文档打开，避免只检查空页面。
    await clickText('查看片段');
    await wait(documentFeedback('片段已读取'));
  } else {
    await wait('document.querySelector("form[aria-label=上传知识文档] input[type=file]") && !document.getElementById("space-number").matches(":disabled")');
    await evaluate(`(() => { const transfer=new DataTransfer(); transfer.items.add(new File([${JSON.stringify(liveText)}], ${JSON.stringify(liveName)}, {type:'text/markdown'})); const input=document.querySelector('.document-manager input[type=file]'); input.files=transfer.files; input.dispatchEvent(new Event('change',{bubbles:true})); })()`);
    await submit('上传知识文档');
    await wait(documentFeedback('原件与草稿版本已保存'));
    assert.equal(await evaluate('document.querySelector(".selected-document h4").textContent'), liveName);
    assert.equal(await evaluate('document.querySelector(".version-list").textContent.includes("待解析 / 草稿")'), true);
    passed('真实页面上传UTF-8原件并读回MySQL草稿版本');
    // 任意后续断言失败也只撤回本次原件；先比对内容，禁止碰同空间其他版本。
    cleanupLive = async () => {
      const result = await evaluate(`(async () => {
        const root='/api/enterprises/${process.env.ENTERPRISE_UI_ID ?? '1'}/knowledge-spaces/${createdId}/documents';
        const rows=await (await fetch(root+'?pageNum=1&pageSize=500',{cache:'no-store'})).json();
        if(rows.code!==0 || rows.data.total>500) throw Error('无法核对测试目录');
        const matches=rows.data.records.filter(item=>item.title===${JSON.stringify(liveName)});
        if(matches.length!==1) throw Error('无法唯一定位本次测试文件');
        const item=matches[0];
        if(item.publishedVersionId===null) return true;
        const original=await fetch(root+'/'+item.id+'/versions/'+item.publishedVersionId+'/original',{cache:'no-store'});
        if(!original.ok || (await original.text())!==${JSON.stringify(liveText)}) throw Error('原件不符，禁止撤回');
        const csrf=await (await fetch('/api/auth/csrf',{cache:'no-store'})).json();
        if(csrf.code!==0 || csrf.data.headerName!=='X-CSRF-TOKEN') throw Error('无法校验请求');
        const response=await (await fetch(root+'/'+item.id+'/lifecycle',{method:'POST',
          headers:{'Content-Type':'application/json',[csrf.data.headerName]:csrf.data.token},
          body:JSON.stringify({versionId:item.publishedVersionId,expectedRevision:item.revision,action:'WITHDRAW'})})).json();
        return response.code===0 && response.data.publishedVersionId===null;
      })()`);
      assert.equal(result, true, '测试版本撤回未完成，须人工核对，不得删除其他记录');
      console.log('失败清理完成：本次测试文件无发布指针，原始记录保留。');
    };
    const ingestTarget = await evaluate(`(async()=>{
      const root='/api/enterprises/${process.env.ENTERPRISE_UI_ID ?? '1'}/knowledge-spaces/${createdId}/documents';
      const docs=await(await fetch(root+'?pageNum=1&pageSize=500',{cache:'no-store'})).json();
      if(docs.code!==0 || docs.data.total>500)throw Error('无法核对本轮测试目录');
      const matches=docs.data.records.filter(d=>d.title===${JSON.stringify(liveName)});
      if(matches.length!==1)throw Error('无法唯一定位本轮测试原件');
      const doc=matches[0];
      const versions=await(await fetch(root+'/'+doc.id+'/versions',{cache:'no-store'})).json();
      if(versions.code!==0 || versions.data.total!==1)throw Error('本轮原件版本不唯一');
      return {documentId:doc.id,versionId:versions.data.records[0].id,revision:doc.revision};
    })()`);
    assert.match(ingestTarget.documentId, /^[1-9]\d*$/);
    assert.match(ingestTarget.versionId, /^[1-9]\d*$/);
    const ingestRoot = `/api/enterprises/${process.env.ENTERPRISE_UI_ID ?? '1'}/knowledge-spaces/${createdId}/documents/${ingestTarget.documentId}/versions/${ingestTarget.versionId}/ingest-jobs`;
    const readLiveIngest = async () => {
      const response = await evaluate(`(async()=>{
        const r=await fetch(${JSON.stringify(ingestRoot)},{cache:'no-store'});
        let result;try{result=await r.json();}catch{}
        return {httpStatus:r.status,code:result?.code,data:result?.data};
      })()`);
      assert.ok(response.httpStatus === 200 && response.code === 0 && Array.isArray(response.data),
        `入库任务预检失败：HTTP=${response.httpStatus}，code=${response.code}；请核对20261019_03两表、gate初值和新版dev后端，不重放schema。`);
      return response.data;
    };
    assert.deepEqual(await readLiveIngest(), [], '新建虚构版本不应已有本人入库任务');
    passed('新入库任务接口实库预检通过，解析前确认本轮版本没有历史任务');
    const assertCompletedIngest = (job, action, revision) => {
      assert.deepEqual(Object.keys(job).sort(), ['id', 'documentId', 'versionId', 'expectedRevision', 'action',
        'status', 'attempts', 'createdMs', 'deadlineMs', 'finishedMs', 'failureCode', 'message'].sort());
      assert.match(job.id, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
      assert.equal(job.documentId, ingestTarget.documentId); assert.equal(job.versionId, ingestTarget.versionId);
      assert.equal(job.expectedRevision, revision); assert.equal(job.action, action);
      assert.equal(job.status, 'SUCCEEDED'); assert.equal(job.attempts, 1);
      assert.equal(job.deadlineMs - job.createdMs, 30000);
      assert.equal(typeof job.finishedMs, 'string'); assert.equal(job.failureCode, null);
      assert.ok(Number(job.finishedMs) >= job.createdMs && Number(job.finishedMs) <= job.deadlineMs);
    };
    await clickText('解析并入库');
    await wait(documentFeedback('片段入库完成'));
    const parsedJobs = await readLiveIngest();
    assert.equal(parsedJobs.length, 1);
    const parsedJob = parsedJobs[0];
    assertCompletedIngest(parsedJob, 'PARSE', ingestTarget.revision);
    passed('真实持久解析任务首次执行成功，30秒截止固定且响应不含租约或原件键');
    const duplicateJobs = await evaluate(`(async()=>{
      const csrf=await(await fetch('/api/auth/csrf',{cache:'no-store'})).json();
      if(csrf.code!==0||csrf.data.headerName!=='X-CSRF-TOKEN')throw Error('请求校验不可用');
      const body=${JSON.stringify({ requestId: parsedJob.id, expectedRevision: parsedJob.expectedRevision, action: 'PARSE' })};
      return Promise.all([1,2].map(()=>fetch(${JSON.stringify(ingestRoot)},{method:'POST',
        headers:{'Content-Type':'application/json',[csrf.data.headerName]:csrf.data.token},body:JSON.stringify(body)}).then(r=>r.json())));
    })()`);
    for (const result of duplicateJobs) { assert.equal(result.code, 0); assert.deepEqual(result.data, parsedJob); }
    assert.deepEqual(await readLiveIngest(), [parsedJob]);
    passed('真实并发同UUID重传返回同一成功任务，不增加执行次数、不延长截止');
    await reload(); await wait('document.querySelector(".primary-nav") !== null');
    await navigateKnowledge(); await wait('document.querySelector(".account-line") !== null');
    await chooseWorkspace( process.env.ENTERPRISE_UI_ID ?? '1'); await fill('space-number', createdId);
    await submit('读取知识空间'); await wait(feedback('读取成功'));
    await wait('document.querySelector(".document-list button") && !document.getElementById("space-number").matches(":disabled")');
    await evaluate(`[...document.querySelectorAll('.document-list button')].find(n=>n.textContent.includes(${JSON.stringify(liveName)})).click()`);
    await wait(documentFeedback('已读取文档'));
    await clickText('查看入库任务'); await wait(documentFeedback('片段入库完成'));
    assert.ok((await evaluate('document.querySelector("section[aria-label=文档入库任务]").textContent')).includes(parsedJob.id));
    assert.deepEqual(await readLiveIngest(), [parsedJob]);
    passed('真实刷新后选原版本恢复同一入库任务，没有自动重提或增加任务');
    confirmLiveRebuild = true;
    await clickText('重建草稿片段'); await wait(documentFeedback('片段入库完成'));
    const rebuiltJobs = await readLiveIngest();
    assert.equal(rebuiltJobs.length, 2);
    const rebuiltJob = rebuiltJobs.find(job => job.id !== parsedJob.id);
    assertCompletedIngest(rebuiltJob, 'REBUILD', ingestTarget.revision + 1);
    assert.deepEqual(rebuiltJobs.find(job => job.id === parsedJob.id), parsedJob);
    assert.match(await evaluate('document.querySelector(".selected-document > p").textContent'), /当前发布版本 无/);
    assert.ok((await evaluate('document.querySelector(".selected-document > p").textContent')).includes(`修订 ${ingestTarget.revision + 2} ·`));
    assert.equal(await evaluate('document.querySelector(".version-list").textContent.includes("片段就绪 / 草稿")'), true);
    liveIngestProof = { documentId: ingestTarget.documentId, versionId: ingestTarget.versionId, parsedJob, rebuiltJob };
    passed('真实草稿重建生成第二任务并原子更新修订，保留原成功任务且不自动发布');
    await clickText('查看片段');
    await wait(documentFeedback('片段已读取'));
    const parts = await evaluate('[...document.querySelectorAll("section[aria-label=文档片段] article")].map(node=>({position:node.querySelector("small").textContent,content:node.querySelector("pre").textContent}))');
    assert.ok(parts.length > 1, '固定资料应产生多个片段');
    for (const part of parts) {
      const offsets = part.position.match(/字符\[(\d+),\s*(\d+)\)/);
      const lines = part.position.match(/第(\d+)—(\d+)行/);
      assert.ok(offsets && lines, '片段须显示原文字符范围及行号');
      const start = Number(offsets[1]), end = Number(offsets[2]);
      assert.equal(part.content, liveText.slice(start, end));
      assert.equal(Number(lines[1]), liveText.slice(0, start).split('\n').length);
      assert.equal(Number(lines[2]), liveText.slice(0, end - 1).split('\n').length);
    }
    passed('真实解析产生多个持久化片段，正文、UTF-16位置及行号逐条对应原件');
    await clickText('发布此版本');
    await wait(documentFeedback('生命周期状态已更新'));
    assert.equal(await evaluate('document.querySelector(".version-list").textContent.includes("已发布")'), true);
    assert.match(await evaluate('document.querySelector(".selected-document > p").textContent'), /当前发布版本 [1-9]\d*/);
    passed('真实页面明确发布并读回当前发布版本指针');
    await reload();
    await wait('document.querySelector(".primary-nav") !== null');
    await navigateKnowledge();
    await wait('document.querySelector(".account-line") !== null');
    await chooseWorkspace( process.env.ENTERPRISE_UI_ID ?? '1');
    await fill('space-number', createdId);
    await submit('读取知识空间');
    await wait(feedback('读取成功'));
    await wait('document.querySelector(".document-list button") && !document.getElementById("space-number").matches(":disabled")');
    await evaluate(`[...document.querySelectorAll('.document-list button')].find(node=>node.textContent.includes(${JSON.stringify(liveName)})).click()`);
    await wait(documentFeedback('已读取文档及当前允许访问的版本'));
    assert.equal(await evaluate('document.querySelector(".selected-document h4").textContent'), liveName);
    assert.equal(await evaluate('document.querySelector(".version-list").textContent.includes("已发布")'), true);
    passed('刷新后重新授权读取MySQL文档和已发布版本，不依赖页面内存');
    await clickText('下载原件');
    await wait(documentFeedback('下载已开始'));
    let downloaded;
    for (let attempt = 0; attempt < 100; attempt++) {
      try { downloaded = await readFile(join(downloadDirectory, liveName)); break; }
      catch (error) { if (error.code !== 'ENOENT') throw error; await delay(100); }
    }
    assert.ok(downloaded, '浏览器原件下载未在10秒内完成');
    assert.deepEqual(downloaded, Buffer.from(liveText, 'utf8'));
    passed('真实下载按钮将原件保存到E盘，下载字节与上传内容完全一致');
    await clickText('查看片段');
    await wait(documentFeedback('片段已读取'));
  }
  for (const width of [320, 390, 768, 1440]) {
    await cdp('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: width < 760 });
    assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true, `${width}px页面溢出`);
  }
  if (process.env.UI_SCREENSHOT_DIR) {
    await mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
    const { data } = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
    await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-documents.png'), Buffer.from(data, 'base64'));
    await evaluate('document.querySelector(".document-manager")?.scrollIntoView({block:"start"})');
    const detail = await cdp('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-upload.png'), Buffer.from(detail.data, 'base64'));
    await evaluate('document.querySelector(".selected-document")?.scrollIntoView({block:"start"})');
    const fragments = await cdp('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-fragments.png'), Buffer.from(fragments.data, 'base64'));
  }
  passed('文档及片段展开时桌面与320/390/768像素布局无水平溢出');
  if (liveWorkspace) {
    const enterprise = process.env.ENTERPRISE_UI_ID ?? '1';
    const root = `/api/enterprises/${enterprise}/knowledge-spaces/${createdId}`;
    const openWorkspace = async () => {
      await evaluate('[...document.querySelectorAll(".primary-nav button")].find(n=>n.textContent.includes("企业问答")).click()');
      await chooseWorkspace(enterprise, true);
      await wait('document.querySelectorAll("#answer-space option").length > 1');
      await evaluate(`(() => {const n=document.getElementById('answer-space');n.value=${JSON.stringify(createdId)};n.dispatchEvent(new Event('change',{bubbles:true}));})()`);
      try {
        await wait('!document.getElementById("knowledge-question").disabled && document.querySelector(".knowledge-answer").textContent.includes("私有会话")');
      } catch (error) {
        // 仅采集安全状态，不读取登录字段、Cookie或历史问题正文。
        const diagnostic = await evaluate(`(async()=>{
          const response=await fetch(${JSON.stringify(root + '/workspace/chats')},{cache:'no-store'});
          let code;try{code=(await response.json()).code;}catch{}
          return {spaceSelected:document.getElementById('answer-space')?.value===${JSON.stringify(createdId)},
            disabled:document.getElementById('knowledge-question')?.disabled,
            notice:document.querySelector('.knowledge-answer > p[role=status]')?.textContent,
            chatsHttpStatus:response.status,chatsCode:code};
        })()`);
        console.error('工作区入口诊断：' + JSON.stringify(diagnostic));
        throw error;
      }
    };
    const modeResponse = await evaluate(`fetch(${JSON.stringify(root + '/answers/mode')},{cache:'no-store'}).then(r=>r.json())`);
    assert.equal(modeResponse.code, 0); assert.equal(modeResponse.data, 'LOCAL_EVIDENCE', '新空间必须默认本地原文，不允许调用模型');
    await openWorkspace();
    await fill('knowledge-question', '员工查询制度版本和审批记录'); await submit('提交企业问题');
    await wait('document.querySelector(".answer-card .answer-state") !== null');
    assert.match(await evaluate('document.querySelector(".answer-state").textContent'), /仅原文摘录/);
    assert.ok((await evaluate('document.querySelector(".answer-evidence").textContent')).includes('员工可查询制度版本'));
    const chatsResponse = await evaluate(`fetch(${JSON.stringify(root + '/workspace/chats')},{cache:'no-store'}).then(r=>r.json())`);
    assert.equal(chatsResponse.code, 0); assert.equal(chatsResponse.data.length, 1);
    const chat = chatsResponse.data[0];
    const history = await evaluate(`fetch(${JSON.stringify(root + '/workspace/chats/' + chat.id)},{cache:'no-store'}).then(r=>r.json())`);
    assert.equal(history.code, 0); assert.equal(history.data.length, 1);
    liveWorkspaceJob = history.data[0];
    assert.equal(liveWorkspaceJob.status, 'COMPLETED'); assert.equal(liveWorkspaceJob.answer.mode, 'LOCAL_EVIDENCE');
    assert.equal(liveWorkspaceJob.deadlineMs - liveWorkspaceJob.createdMs, 30000);
    passed('真实MySQL受理与后台完成任务，默认本地原文且首次30秒截止');
    const duplicates = await evaluate(`(async()=>{
      const csrf=await(await fetch('/api/auth/csrf',{cache:'no-store'})).json();
      if(csrf.code!==0||csrf.data.headerName!=='X-CSRF-TOKEN')throw Error('请求校验不可用');
      const body=${JSON.stringify({ chatId: liveWorkspaceJob.chatId, requestId: liveWorkspaceJob.id, question: liveWorkspaceJob.question })};
      return Promise.all([1,2].map(()=>fetch(${JSON.stringify(root + '/workspace/jobs')},{method:'POST',headers:{'Content-Type':'application/json',[csrf.data.headerName]:csrf.data.token},body:JSON.stringify(body)}).then(r=>r.json())));
    })()`);
    for (const result of duplicates) { assert.equal(result.code, 0); assert.equal(result.data.id, liveWorkspaceJob.id); assert.equal(result.data.deadlineMs, liveWorkspaceJob.deadlineMs); }
    passed('真实MySQL并发重复受理返回原任务，不续时限');
    await reload(); await wait('document.querySelector(".primary-nav") !== null'); await openWorkspace();
    await wait('document.querySelector(".answer-card .answer-state") !== null');
    assert.equal(await evaluate('document.querySelectorAll(".answer-card").length'), 1);
    assert.ok((await evaluate('document.querySelector(".answer-card").textContent')).includes(liveWorkspaceJob.id));
    passed('真实刷新由MySQL恢复同一会话与完成任务，不靠浏览器正文缓存');
    await evaluate('(() => {const n=document.querySelector(".answer-card details textarea");n.value="本轮虚构资料反馈：请核对制度版本说明";n.dispatchEvent(new Event("input",{bubbles:true}));})()');
    await evaluate('[...document.querySelectorAll(".answer-card button")].find(n=>n.textContent.trim()==="保存反馈").click()');
    await wait('document.querySelector(".answer-card").textContent.includes("反馈已保存")');
    await evaluate('document.querySelector(".feedback-governance").open=true');
    await evaluate('[...document.querySelectorAll(".feedback-governance button")].find(n=>n.textContent.includes("读取待办")).click()');
    await wait('document.querySelector(".feedback-governance article") !== null');
    assert.ok((await evaluate('document.querySelector(".feedback-governance article").textContent')).includes(liveWorkspaceJob.question));
    await evaluate('(() => {const n=document.querySelector(".feedback-governance textarea");n.value="已核验本轮合成资料；此记录仅供联调，不改动制度。";n.dispatchEvent(new Event("input",{bubbles:true}));})()');
    await evaluate('[...document.querySelectorAll(".feedback-governance button")].find(n=>n.textContent.trim()==="标记已处理").click()');
    await wait('document.querySelector(".feedback-governance article").textContent.includes("RESOLVED")');
    const feedbacks = await evaluate(`fetch(${JSON.stringify(root + '/workspace/feedback')},{cache:'no-store'}).then(r=>r.json())`);
    assert.equal(feedbacks.code, 0); assert.equal(feedbacks.data.length, 1); assert.equal(feedbacks.data[0].revision, 2);
    assert.equal(feedbacks.data[0].jobId, liveWorkspaceJob.id);
    passed('真实反馈写入三表关联，维护者处理后revision为2且关联原问题');
    await navigateKnowledge(); await wait('document.querySelector(".account-line") !== null');
    await chooseWorkspace( enterprise); await fill('space-number', createdId); await submit('读取知识空间');
    await wait('document.querySelector(".document-list button") && !document.getElementById("space-number").matches(":disabled")');
    await evaluate(`[...document.querySelectorAll('.document-list button')].find(n=>n.textContent.includes(${JSON.stringify(liveName)})).click()`);
    await wait(documentFeedback('已读取文档及当前允许访问的版本'));
  }
  if (liveAnswer) {
    await evaluate('[...document.querySelectorAll(".primary-nav button")].find(node=>node.textContent.includes("企业问答")).click()');
    await chooseWorkspace(process.env.ENTERPRISE_UI_ID ?? '1', true);
    await wait('document.querySelectorAll("#answer-space option").length > 1');
    await evaluate(`(() => { const node=document.getElementById('answer-space'); node.value=${JSON.stringify(acceptanceSpace)}; node.dispatchEvent(new Event('change',{bubbles:true})); })()`);
    await wait('!document.getElementById("knowledge-question").disabled');
    await fill('knowledge-question', '加班餐补每天上限多少？申请需要谁审批？到账时间是多久？');
    await submit('提交企业问题');
    await wait('document.querySelector(".answer-card .answer-state") !== null || document.querySelector(".answer-error") !== null', 450);
    const assessment = await evaluate(`(() => {
      const card=document.querySelector('.answer-card');
      return {parts:card.querySelectorAll('.answer-part').length, text:card.textContent,
        citations:card.querySelectorAll('.citation-button').length, error:!!card.querySelector('.answer-error'),
        timing:card.querySelector('.answer-state small')?.textContent,
        details:[...card.querySelectorAll('.answer-part')].map(part=>({
          status:part.querySelector('h4 small')?.textContent,
          text:[...part.querySelectorAll('.answer-text')].map(node=>node.textContent).join(' '),
          citations:part.querySelectorAll('.citation-button').length,
          gaps:[...part.querySelectorAll('li')].map(node=>node.textContent)}))};
    })()`);
    assert.equal(assessment.error, false, '真实模型回答请求失败，查看页面提示，不自动重试');
    assert.equal(assessment.parts, 3, '真实模型未按原文覆盖三个问题');
    assert.ok(assessment.citations >= 2 && assessment.text.includes('30') && /资料不足|部分可回答/.test(assessment.text),
      '真实三问未同时满足金额、引用及资料缺口要求');
    assert.equal(assessment.details[0].status, '已有依据的回答');
    assert.match(assessment.details[0].text, /30\s*元/, '第一问须明确餐补金额');
    assert.ok(assessment.details[0].citations >= 1, '第一问必须有本项引用');
    assert.equal(assessment.details[1].status, '已有依据的回答');
    assert.match(assessment.details[1].text, /直属主管/, '第二问须明确资料中的审批人');
    assert.ok(assessment.details[1].citations >= 1, '第二问必须有本项引用');
    assert.equal(assessment.details[2].status, '资料不足', '第三问不能编造到账时间');
    assert.equal(assessment.details[2].text, '', '无依据的问题不能输出结论段落');
    assert.ok(assessment.details[2].gaps.length > 0, '第三问须解释缺少的资料');
    passed('真实MySQL检索与已配置模型完成三问：两项有引用，到账时间明确资料不足');
    await evaluate('document.querySelector(".citation-button").click()');
    await wait('document.querySelector(".source-view pre") !== null');
    assert.ok(liveText.includes(await evaluate('document.querySelector(".source-view pre").textContent')));
    passed('真实回答引用再次通过授权，并逐字对应刚上传的虚构原文');
    if (process.env.UI_SCREENSHOT_DIR) {
      const { data } = await cdp('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
      await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-answer-live.png'), Buffer.from(data, 'base64'));
      await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-answer-live-result.json'),
        JSON.stringify({ testFile: liveName, enterpriseId: process.env.ENTERPRISE_UI_ID ?? '1',
          spaceId: acceptanceSpace, timing: assessment.timing, parts: assessment.details }, null, 2) + '\n', 'utf8');
    }
    await navigateKnowledge();
    await wait('document.querySelector(".account-line") !== null');
    await chooseWorkspace( process.env.ENTERPRISE_UI_ID ?? '1');
    await fill('space-number', createdId);
    await submit('读取知识空间');
    await wait('document.querySelector(".document-list button") && !document.getElementById("space-number").matches(":disabled")');
    await evaluate(`[...document.querySelectorAll('.document-list button')].find(node=>node.textContent.includes(${JSON.stringify(liveName)})).click()`);
    await wait(documentFeedback('已读取文档及当前允许访问的版本'));
  }
  if (live) {
    confirmLiveWithdrawal = true;
    await clickText('撤回');
    await wait(documentFeedback('生命周期状态已更新'));
    assert.equal(await evaluate('document.querySelector(".version-list").textContent.includes("已撤回")'), true);
    assert.match(await evaluate('document.querySelector(".selected-document > p").textContent'), /当前发布版本 无/);
    assert.equal(await evaluate('document.querySelector("section[aria-label=文档片段]") === null'), true);
    cleanupLive = undefined;
    passed('真实撤回后读回空发布指针与已撤回状态，并清除旧片段展示');
    if (liveWorkspaceJob) {
      const old = await evaluate(`fetch(${JSON.stringify(`/api/enterprises/${process.env.ENTERPRISE_UI_ID ?? '1'}/knowledge-spaces/${createdId}/workspace/jobs/${liveWorkspaceJob.id}`)},{cache:'no-store'}).then(r=>r.json())`);
      assert.equal(old.code, 0); assert.equal(old.data.status, 'COMPLETED'); assert.equal(old.data.answer, null);
      assert.match(old.data.message, /暂不展示/);
      passed('真实资料撤回后历史回答被隐藏，不通过持久结果绕过有效期');
    }
  }
  if (!live) {
    await clickText('撤回');
    await wait(documentFeedback('生命周期状态已更新'));
    assert.equal(await evaluate('document.querySelector(".version-list").textContent.includes("已撤回")'), true);
    assert.equal(await evaluate('document.querySelector("section[aria-label=文档片段]") === null'), true);
    passed('明确确认撤回后刷新状态并清除旧片段展示');
    mode = 'reader';
    await clickText('刷新资料与权限');
    await wait(documentFeedback('资料状态已刷新'));
    assert.equal(await evaluate('document.querySelector("form[aria-label=上传知识文档]") === null'), true);
    assert.equal(await evaluate('document.querySelector(".document-manager details") === null'), true);
    assert.equal(await evaluate('document.querySelector("section[aria-label=向量索引管理]") === null'), true);
    mode = 'ok';
    passed('维护权撤销后刷新隐藏上传与授权管理表单');
    mode = 'denied';
    await submit('读取知识空间');
    await wait(feedback('不存在或不可访问'));
    assert.equal(await evaluate('document.querySelector(".space-result") === null'), true);
    assert.equal(await evaluate('document.body.textContent.includes("SECRET_SQL_TOKEN")'), false);
    mode = 'ok';
    passed('HTTP200中的无权业务码被正确识别，清除旧内容且不泄露原始异常');
  }
  for (const width of [320, 390, 768, 1440]) {
    await cdp('Emulation.setDeviceMetricsOverride', { width, height: 1000, deviceScaleFactor: 1, mobile: width < 760 });
    assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true, `${width}px页面溢出`);
    assert.equal(await evaluate('document.querySelector(".enterprise-workspace").scrollWidth <= document.querySelector(".enterprise-workspace").clientWidth'), true);
  }
  if (process.env.UI_SCREENSHOT_DIR) {
    await mkdir(process.env.UI_SCREENSHOT_DIR, { recursive: true });
    const { data } = await cdp('Page.captureScreenshot', { format: 'png' });
    await writeFile(join(process.env.UI_SCREENSHOT_DIR, live ? 'knowledge-live.png' : 'knowledge-offline.png'), Buffer.from(data, 'base64'));
  }
  passed('桌面与320/390/768像素布局无水平溢出');
  if (!live) {
    mode = 'expired';
    await submit('读取知识空间');
    await wait('document.querySelector(".account-line") === null && document.getElementById("enterprise-password") !== null');
    assert.equal(await evaluate('document.querySelector(".space-result") === null'), true);
    passed('登录过期立即清除私有展示并回到登录入口');
    mode = 'ok';
    await fill('enterprise-username', 'fictitious-admin');
    await fill('enterprise-password', 'not-a-real-password');
    await submit('企业账号登录');
    await wait('document.querySelector(".account-line") !== null');
  }
  if (!live) {
    await cdp('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false });
    await evaluate('[...document.querySelectorAll(".primary-nav button")].find(node=>node.textContent.includes("企业问答")).click()');
    await chooseWorkspace('1', true);
    await wait('document.querySelectorAll("#answer-space option").length > 1');
    await evaluate(`(() => { const node=document.getElementById('answer-space'); node.value=${JSON.stringify(fixture.id)}; node.dispatchEvent(new Event('change',{bubbles:true})); })()`);
    await wait('!document.getElementById("knowledge-question").disabled');
    if (process.env.UI_SCREENSHOT_DIR) {
      await delay(250);
      const { data } = await cdp('Page.captureScreenshot', { format: 'png' });
      await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-chat-welcome.png'), Buffer.from(data, 'base64'));
    }
    await fill('knowledge-question', '餐补多少？怎么申请？打车呢？');
    await submit('提交企业问题');
    await wait('document.querySelectorAll(".answer-part").length === 3');
    assert.ok((await evaluate('document.querySelector(".answer-card").textContent')).includes('资料不足'));
    assert.ok((await evaluate('document.querySelector(".answer-card").textContent')).includes('部分可回答'));
    passed('企业三问分别显示可答、部分和无资料，不以整体成功掩盖缺口');
    await evaluate('document.querySelector(".citation-button").click()');
    await wait('document.querySelector(".source-view pre") !== null');
    assert.equal(await evaluate('document.querySelector(".source-view pre").textContent'), answerContent);
    if (process.env.UI_SCREENSHOT_DIR) {
      const { data } = await cdp('Page.captureScreenshot', { format: 'png' });
      await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-chat-source.png'), Buffer.from(data, 'base64'));
    }
    assert.equal(await evaluate('window.__knowledgeUnsafe === undefined'), true);
    answerScenario = 'revoked';
    await evaluate('document.querySelector(".citation-button").click()');
    await wait('document.querySelector(".source-view")?.textContent.includes("不存在或不可访问")');
    assert.equal(await evaluate('document.querySelector(".source-view pre") === null'), true);
    passed('引用重新走私有权限校验，撤权不退回旧原文，HTML按文字展示');
    answerScenario = 'hold';
    await fill('knowledge-question', '第一条补充'); await submit('提交企业问题');
    await wait('document.querySelectorAll(".answer-progress").length === 1');
    for (let index = 0; index < 100 && heldAnswers.length < 1; index++) await delay(20);
    assert.equal(heldAnswers.length, 1);
    await fill('knowledge-question', '第二条补充'); await submit('提交企业问题');
    await wait('document.querySelectorAll(".answer-progress").length === 2');
    for (let index = 0; index < 100 && heldAnswers.length < 2; index++) await delay(20);
    assert.equal(heldAnswers.length, 2);
    assert.equal(await evaluate('document.querySelector("form[aria-label=提交企业问题] button").disabled'), true);
    await fulfill(heldAnswers[1], 200, { code: 0, data: answerFixture('第二条补充') });
    await fulfill(heldAnswers[0], 200, { code: 0, data: answerFixture('第一条补充') });
    await wait('document.querySelectorAll(".answer-progress").length === 0');
    passed('第一问等待时仍可发送补充，两条有界并行且乱序返回不串卡片');
    answerScenario = 'unavailable';
    await fill('knowledge-question', '模型故障测试'); await submit('提交企业问题');
    await wait('[...document.querySelectorAll(".answer-state")].some(node=>node.textContent.includes("模型未完成"))');
    answerScenario = 'busy';
    await fill('knowledge-question', '繁忙测试'); await submit('提交企业问题');
    await wait('document.querySelector(".answer-error") !== null');
    assert.equal(answerCalls, 5);
    assert.equal(await evaluate('document.querySelector(".knowledge-answer").textContent.includes("SECRET")'), false);
    assert.equal(publicCalls, 0);
    passed('模型失败与通道繁忙有终态，不自动重试、不转公开搜索、不泄露上游正文');
    for (const width of [320, 390, 768]) {
      await cdp('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
      assert.equal(await evaluate('document.querySelector(".enterprise-workspace").scrollWidth <= document.querySelector(".enterprise-workspace").clientWidth'), true);
    }
    if (process.env.UI_SCREENSHOT_DIR) {
      const { data } = await cdp('Page.captureScreenshot', { format: 'png' });
      await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-answer-offline.png'), Buffer.from(data, 'base64'));
    }
    passed('企业回答含引用和错误状态时，小屏布局无水平溢出');
    workspaceEnabled = true;
    const openDurable = async () => {
      await evaluate('[...document.querySelectorAll(".primary-nav button")].find(node=>node.textContent.includes("企业问答")).click()');
      await chooseWorkspace('1', true);
      await wait('document.querySelectorAll("#answer-space option").length > 1');
      await evaluate(`(() => { const node=document.getElementById('answer-space'); node.value=${JSON.stringify(fixture.id)}; node.dispatchEvent(new Event('change',{bubbles:true})); })()`);
      await wait('!document.getElementById("knowledge-question").disabled && document.querySelector(".knowledge-answer").textContent.includes("私有会话")');
    };
    await navigateKnowledge(); await wait('document.querySelector(".account-line") !== null'); await openDurable();
    await fill('knowledge-question', '持久任务餐补与审批三问'); await submit('提交企业问题');
    await wait('document.querySelectorAll(".answer-part").length === 3');
    assert.equal(workspaceWrites, 1);
    assert.ok((await evaluate('document.querySelector(".answer-card").textContent')).includes([...durableJobs.keys()][0]));
    passed('持久问答先受理任务再查询终态，回答仍保留逐问状态与出处');
    const contextMessage = '已按同会话历史问题关联话题，请结合引用核对。<script>window.__contextUnsafe=1</script>';
    [...durableJobs.values()][0].answer.message = contextMessage;
    await reload(); await wait('document.querySelector(".primary-nav") !== null'); await openDurable();
    await wait('document.querySelectorAll(".answer-part").length === 3');
    assert.equal(workspaceWrites, 1); assert.equal(answerCalls, 5);
    passed('真实浏览器刷新后读取原会话和原任务，不再次提交或调用回答接口');
    assert.ok((await evaluate('document.querySelector(".answer-context-note").textContent')).includes('不会强行继承旧问题'));
    assert.ok((await evaluate('document.querySelector(".answer-card").textContent')).includes(contextMessage));
    assert.equal(await evaluate('window.__contextUnsafe === undefined'), true);
    passed('上下文提示随原任务刷新恢复，提示中的HTML仅作文字展示');
    await evaluate('(() => { const n=document.querySelector(".answer-card details textarea"); n.value="测试反馈：制度缺少到账说明<script>window.__feedbackUnsafe=1</script>"; n.dispatchEvent(new Event("input",{bubbles:true})); })()');
    await evaluate('[...document.querySelectorAll(".answer-card button")].find(n=>n.textContent.trim()==="保存反馈").click()');
    await wait('document.querySelector(".answer-card").textContent.includes("反馈已保存")');
    await evaluate('document.querySelector(".feedback-governance").open=true');
    await evaluate('[...document.querySelectorAll(".feedback-governance button")].find(n=>n.textContent.includes("读取待办")).click()');
    await wait('document.querySelector(".feedback-governance article") !== null');
    assert.equal(await evaluate('window.__feedbackUnsafe === undefined'), true);
    await evaluate('(() => { const n=document.querySelector(".feedback-governance textarea"); n.value="已核对，待负责人补充制度"; n.dispatchEvent(new Event("input",{bubbles:true})); })()');
    await evaluate('[...document.querySelectorAll(".feedback-governance button")].find(n=>n.textContent.trim()==="标记已处理").click()');
    await wait('document.querySelector(".feedback-governance article").textContent.includes("RESOLVED")');
    assert.equal(durableFeedback.length, 1); assert.equal(durableFeedback[0].revision, 2);
    passed('反馈关联原问题，由维护者带版本处理，HTML只按文本展示');
    holdWorkspace = true;
    await fill('knowledge-question', '取消持久任务测试'); await submit('提交企业问题');
    await wait('document.querySelectorAll(".answer-progress").length === 1');
    await wait('[...document.querySelectorAll(".answer-card")].at(-1).textContent.includes("已受理")');
    await evaluate('[...document.querySelectorAll(".answer-card button")].find(n=>n.textContent.trim()==="取消此任务").click()');
    try {
      await wait('document.querySelectorAll(".answer-progress").length === 0 && document.querySelector(".answer-timeline").textContent.includes("任务已取消")');
    } catch (error) {
      // 本分支仅用于拦截的合成夹具，失败时保留状态，避免用放宽断言掩盖问题。
      console.error('取消任务诊断', { jobs: [...durableJobs.values()].map(job => ({ status: job.status,
        elapsedLimitMs: job.deadlineMs - job.createdMs, hasAnswer: job.answer != null })), interceptionErrors,
      cards: await evaluate('[...document.querySelectorAll(".answer-card")].map(n=>({ progress:!!n.querySelector(".answer-progress"), error:n.querySelector(".answer-error")?.textContent, text:n.textContent }))') });
      throw error;
    }
    assert.equal(workspaceWrites, 2); assert.equal(publicCalls, 0);
    passed('持久任务支持独立取消，取消后停止进度等待且不触发公开搜索');
    holdWorkspace = false; loseWorkspaceResponse = true;
    await fill('knowledge-question', '已受理但响应丢失'); await submit('提交企业问题');
    await wait('[...document.querySelectorAll(".answer-card button")].some(n=>n.textContent.includes("按原编号重发"))');
    assert.equal(workspaceWrites, 3);
    await evaluate('[...document.querySelectorAll(".answer-card button")].find(n=>n.textContent.includes("按原编号重发")).click()');
    await wait('document.querySelectorAll(".answer-card")[2].querySelector(".answer-state") !== null');
    assert.equal(workspaceWrites, 3);
    passed('提交响应丢失时先核对原任务，已受理不重发POST');
    rejectWorkspaceBeforeAccept = true;
    await fill('knowledge-question', '尚未受理时断线'); await submit('提交企业问题');
    await wait('[...document.querySelectorAll(".answer-card button")].some(n=>n.textContent.includes("按原编号重发"))');
    assert.equal(workspaceWrites, 4);
    const beforeRetry = durableJobs.size;
    await evaluate('[...document.querySelectorAll(".answer-card button")].find(n=>n.textContent.includes("按原编号重发")).click()');
    await wait('document.querySelectorAll(".answer-card")[3].querySelector(".answer-state") !== null');
    assert.equal(workspaceWrites, 5); assert.equal(durableJobs.size, beforeRetry + 1);
    assert.equal(publicCalls, 0);
    passed('明确查无任务后仅人工按原编号重发，不自动重试或切同步接口');
    workspaceEnabled = false;
    await navigateKnowledge(); await wait('document.querySelector(".account-line") !== null');
    await evaluate('[...document.querySelectorAll(".primary-nav button")].find(n=>n.textContent.includes("企业问答")).click()');
    await chooseWorkspace('1', true);
    await wait('document.querySelectorAll("#answer-space option").length > 1');
    await evaluate(`(() => { const n=document.getElementById('answer-space'); n.value=${JSON.stringify(fixture.id)}; n.dispatchEvent(new Event('change',{bubbles:true})); })()`);
    await wait('!document.getElementById("knowledge-question").disabled');
    for (const [scenario, label] of [['scope-unknown', '业务范围待确认'], ['scope-unavailable', '范围识别不可用']]) {
      answerScenario = scenario;
      await fill('knowledge-question', '这个能办吗？'); await submit('提交企业问题');
      await wait(`[...document.querySelectorAll('.answer-state')].some(n=>n.textContent.includes(${JSON.stringify(label)}))`);
      assert.equal(await evaluate('document.querySelector(".answer-scope-switch") === null'), true);
      assert.equal(publicCalls, 0);
      passed(`${label}有明确终态，不显示确定校外标签或公开转发按钮`);
    }
    answerScenario = 'school-mixed';
    await fill('knowledge-question', '餐补多少？宠物保险呢？多久到账？'); await submit('提交企业问题');
    await wait('document.querySelector(".answer-scope-switch") !== null');
    const last = await evaluate('[...document.querySelectorAll(".answer-card")].at(-1).textContent');
    assert.ok(last.includes('已有依据的回答') && last.includes('超出学校业务范围') && last.includes('资料不足'));
    const parts = await evaluate('[...document.querySelectorAll(".answer-card")].at(-1) && [...[...document.querySelectorAll(".answer-card")].at(-1).querySelectorAll(".answer-part h4")].map(n=>({number:n.querySelector(".part-number").textContent,text:n.textContent}))');
    assert.deepEqual(parts.map(part => part.number), ['01', '02', '03']);
    assert.ok(parts[0].text.includes('餐补多少？') && parts[1].text.includes('宠物保险呢？') && parts[2].text.includes('多久到账？'));
    assert.ok(last.includes('不携带当前问题、学校原文或会话历史'));
    passed('校内、校外与缺资料混合三问按原序显示，各自状态不互相覆盖');
    const writesBeforeSwitch = answerCalls;
    await evaluate('document.querySelector(".answer-scope-switch button").click()');
    await wait('document.getElementById("question") !== null');
    assert.equal(await evaluate('document.getElementById("question").value'), '');
    assert.equal(publicCalls, 0); assert.equal(answerCalls, writesBeforeSwitch);
    passed('用户主动切换公开问答仅导航，输入框为空、没有公开提交或重复企业请求');
    mode = 'ok';
    await navigateKnowledge();
    await wait('document.querySelector(".account-line") !== null');
  }
  await evaluate('document.querySelector(".account-line button").click()');
  await wait(feedback('已退出登录'));
  assert.equal(await evaluate('document.querySelector(".space-result") === null'), true);
  const storage = await evaluate('Object.keys(localStorage).concat(Object.keys(sessionStorage))');
  assert.equal(storage.some(key => /token|password|session|account|knowledge/i.test(key)), false);
  passed('退出清除私有展示，未把账号、密码、令牌或空间写入浏览器存储');
  assert.deepEqual(exceptions, []);
  assert.deepEqual(interceptionErrors, []);
  if (liveIngestProof && process.env.UI_SCREENSHOT_DIR) {
    await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-ingest-live-result.json'), JSON.stringify({
      checkedAt: new Date().toISOString(), enterpriseId: process.env.ENTERPRISE_UI_ID ?? '1', spaceId: createdId,
      testFile: liveName, ...liveIngestProof, checks, sourceWithdrawn: true,
      storage: '真实MySQL与本机HTTP，不调用模型/搜索；取消与失效租约故障仍仅离线验证'
    }, null, 2) + '\n', 'utf8');
  }
  if (liveWorkspaceJob && process.env.UI_SCREENSHOT_DIR) {
    await writeFile(join(process.env.UI_SCREENSHOT_DIR, 'knowledge-workspace-live-result.json'), JSON.stringify({
      checkedAt: new Date().toISOString(), enterpriseId: process.env.ENTERPRISE_UI_ID ?? '1', spaceId: createdId,
      testFile: liveName, jobId: liveWorkspaceJob.id, chatId: liveWorkspaceJob.chatId,
      createdMs: liveWorkspaceJob.createdMs, deadlineMs: liveWorkspaceJob.deadlineMs,
      mode: liveWorkspaceJob.answer.mode, checks, sourceWithdrawn: true, historicalAnswerHidden: true,
      feedbackRevision: 2, providerCalls: 0, storage: '真实MySQL与本机HTTP，未调用模型/搜索'
    }, null, 2) + '\n', 'utf8');
  }
  if (live) console.log(`真实页面联调成功；保留的测试空间编号=${createdId}，名称=${testName}，测试原件=${liveName}，最终状态已撤回。${liveAnswer ? '已对虚构资料调用模型，未调用公开搜索。' : '未调用模型/搜索。'}未删除既有数据。`);
  console.log(`企业页面检查完成：${checks}/${checks}；模式=${live ? '真实本机后端' : '浏览器拦截接口'}。`);
  }
} finally {
  if (cleanupLive) await cleanupLive().catch(() => {
    console.error('本轮标记测试数据未能自动收尾，请人工核对；未删除任何数据。');
    process.exitCode = 1;
  });
  if (cdp) await cdp('Browser.close').catch(() => {});
  socket?.close();
  if (!browser.killed) browser.kill();
}
