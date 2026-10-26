/** 企业私有接口：Session仅由浏览器管理，CSRF仅在本次操作内存中使用。 */
import { chunkView, documentView, grantsView, pageView, versionView } from './knowledge.ts';
import { answerView, evidenceView } from './knowledge-answer.ts';
import { workspaceChats, workspaceHistory, workspaceJob, workspaceUuid, feedbackList } from './knowledge-workspace.ts';
import type { UploadMetadata } from './knowledge';
import { ingestJob, ingestJobs } from './knowledge-ingest.ts';
import { departmentName, departmentResult, departmentsView, employeeResult, membersView, memberView, organizationVersion } from './organization.ts';
import { affairApplication as applicationView, affairEligibility as eligibilityView, affairFinance as financeRow,
  affairMembers as memberList, affairPending as pendingRow, affairReceipt as receiptView, affairRefund as refundView,
  affairRule as ruleView, affairSettlement as settlementRow, affairSettlementView as settlementView,
  affairSummary as summaryRow, affairsPage } from './affairs.ts';
export interface EnterpriseWorkspace { enterpriseId: string; enterpriseName: string; role: 'EMPLOYEE' | 'ADMIN' | 'FINANCE' }
/** 分摊输入：成员编号与分摊金额均以字符串承接，避免Long精度问题。 */
export interface AffairShareInput { memberId: string; shareCents: string }
export interface Account { userId: string; enterprises?: EnterpriseWorkspace[] }
export interface KnowledgeSpace { id: string; name: string; permissionVersion: number }
export interface SpacePage { records: KnowledgeSpace[]; total: number; pageNum: number; pageSize: number; pages: number }

export class EnterpriseError extends Error {
  readonly code: number;
  readonly uncertain: boolean;
  constructor(message: string, code = 0, uncertain = false) {
    super(message);
    this.code = code;
    this.uncertain = uncertain;
  }
}

const object = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

export function positiveId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value)
    && BigInt(value) <= 9223372036854775807n;
}

/** 文档修订与发布指针修订分开收发；均保持十进制字符串，禁止经过Number舍入。 */
export interface VectorJob {
  id: string; documentId: string; versionId: string; expectedRevision: string;
  status: 'QUEUED' | 'RUNNING' | 'VERIFIED' | 'CANCELLED' | 'TIMED_OUT' | 'FAILED';
  expectedChunks: number; verifiedChunks: number; createdMs: number; deadlineMs: number;
  finishedMs: string | null; failureCode: string | null; message: string;
}
export interface VectorPublication {
  documentId: string; jobId: string | null; revision: string;
  status: 'UNPUBLISHED' | 'READY' | 'STALE' | 'WITHDRAWN'; updatedMs: string | null; message: string;
}
const vectorFailures = ['ACCESS_REVOKED', 'REVISION_CONFLICT', 'MODEL_CHANGED', 'MANIFEST_MISMATCH',
  'WRITE_UNCONFIRMED', 'VECTOR_UNAVAILABLE', 'INTERNAL_ERROR', 'TOTAL_TIMEOUT', 'LEASE_LOST'];
const decimalLong = (value: unknown): value is string => value === '0' || positiveId(value);
const safeMillis = (value: unknown): value is number => Number.isSafeInteger(value) && Number(value) >= 0;
const vectorText = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && value.length <= 300;
function badVector(): never { throw new EnterpriseError('向量接口索引数据没能正确读取，请稍后重试；若持续出现，请联系管理员。'); }

function vectorJob(value: unknown, document: string, version: string, requestId: string, revision?: string): VectorJob {
  if (!object(value) || value.id !== requestId || value.documentId !== document || value.versionId !== version
    || !positiveId(value.expectedRevision) || (revision !== undefined && value.expectedRevision !== revision)
    || typeof value.status !== 'string' || !['QUEUED', 'RUNNING', 'VERIFIED', 'CANCELLED', 'TIMED_OUT', 'FAILED'].includes(value.status)
    || !Number.isInteger(value.expectedChunks) || Number(value.expectedChunks) < 1 || Number(value.expectedChunks) > 1000
    || !Number.isInteger(value.verifiedChunks) || !safeMillis(value.createdMs) || !safeMillis(value.deadlineMs)
    || value.deadlineMs - value.createdMs !== 120000 || !vectorText(value.message)
    || !(value.finishedMs === null || decimalLong(value.finishedMs))
    || !(value.failureCode === null || (typeof value.failureCode === 'string' && vectorFailures.includes(value.failureCode)))) return badVector();
  const active = value.status === 'QUEUED' || value.status === 'RUNNING';
  if (active !== (value.finishedMs === null)
    || (value.finishedMs !== null && BigInt(value.finishedMs as string) < BigInt(value.createdMs))
    || (value.status === 'VERIFIED' ? value.verifiedChunks !== value.expectedChunks : value.verifiedChunks !== 0)
    || (value.status === 'VERIFIED' && BigInt(value.finishedMs as string) >= BigInt(value.deadlineMs))
    || (['FAILED', 'TIMED_OUT'].includes(String(value.status)) ? value.failureCode === null : value.failureCode !== null)
    || (value.status === 'TIMED_OUT' && (value.failureCode !== 'TOTAL_TIMEOUT'
      || BigInt(value.finishedMs as string) < BigInt(value.deadlineMs)))) return badVector();
  // 显式投影公开字段；上游即使多返回内部字段，也不把它们传入页面状态。
  return { id: requestId, documentId: document, versionId: version, expectedRevision: value.expectedRevision,
    status: value.status as VectorJob['status'], expectedChunks: Number(value.expectedChunks), verifiedChunks: Number(value.verifiedChunks),
    createdMs: value.createdMs, deadlineMs: value.deadlineMs, finishedMs: value.finishedMs as string | null,
    failureCode: value.failureCode as string | null, message: value.message };
}

function vectorPublication(value: unknown, document: string): VectorPublication {
  if (!object(value) || value.documentId !== document || !decimalLong(value.revision)
    || typeof value.status !== 'string' || !['UNPUBLISHED', 'READY', 'STALE', 'WITHDRAWN'].includes(value.status) || !vectorText(value.message)
    || !(value.jobId === null || workspaceUuid(value.jobId))
    || !(value.updatedMs === null || decimalLong(value.updatedMs))) return badVector();
  if (value.status === 'UNPUBLISHED'
    ? value.revision !== '0' || value.jobId !== null || value.updatedMs !== null
    : value.revision === '0' || value.updatedMs === null || (value.status === 'WITHDRAWN') !== (value.jobId === null)) return badVector();
  return { documentId: document, jobId: value.jobId as string | null, revision: value.revision,
    status: value.status as VectorPublication['status'], updatedMs: value.updatedMs as string | null, message: value.message };
}

function pointerRevision(value: string): string {
  // 指针写入会加一，Long.MAX_VALUE只允许读取，不能再用作写入预期值。
  if (!decimalLong(value) || value === '9223372036854775807') throw new EnterpriseError('索引发布预期修订号须为可递增的非负整数字符串。');
  return value;
}

function account(value: unknown): Account {
  if (!object(value) || !positiveId(value.userId)) throw new EnterpriseError('账号数据没能正确读取，请联系管理员。');
  // 兼容旧接口，但页面不会据此猜测企业或要求员工填写数据库编号。
  if (value.enterprises === undefined) return { userId: value.userId };
  if (!Array.isArray(value.enterprises) || value.enterprises.length > 100)
    throw new EnterpriseError('企业归属数据没能正确读取，请联系管理员。');
  const seen = new Set<string>();
  const enterprises = value.enterprises.map(item => {
    if (!object(item) || !positiveId(item.enterpriseId) || seen.has(item.enterpriseId)
      || typeof item.enterpriseName !== 'string' || !item.enterpriseName.trim() || item.enterpriseName.length > 128
      || !['EMPLOYEE', 'ADMIN', 'FINANCE'].includes(String(item.role)))
      throw new EnterpriseError('企业归属数据没能正确读取，请联系管理员。');
    seen.add(item.enterpriseId);
    return { enterpriseId: item.enterpriseId, enterpriseName: item.enterpriseName, role: item.role } as EnterpriseWorkspace;
  });
  return { userId: value.userId, enterprises };
}

function space(value: unknown): KnowledgeSpace {
  if (!object(value) || !positiveId(value.id) || typeof value.name !== 'string'
    || !value.name.trim() || value.name.length > 128 || !Number.isSafeInteger(value.permissionVersion)
    || Number(value.permissionVersion) <= 0) throw new EnterpriseError('空间数据没能正确读取，请联系管理员。');
  return { id: value.id, name: value.name, permissionVersion: Number(value.permissionVersion) };
}

function spacePage(value: unknown): SpacePage {
  if (!object(value) || !Array.isArray(value.records)
    || !['total', 'pageNum', 'pageSize', 'pages'].every(key => Number.isSafeInteger(value[key]))
    || Number(value.total) < 0 || Number(value.pageNum) < 1 || Number(value.pageSize) < 1
    || Number(value.pageSize) > 500 || Number(value.pages) < 0 || value.records.length > Number(value.pageSize))
    throw new EnterpriseError('空间列表数据没能正确读取，请联系管理员。');
  return { records: value.records.map(space), total: Number(value.total), pageNum: Number(value.pageNum),
    pageSize: Number(value.pageSize), pages: Number(value.pages) };
}

/** 默认10秒总等待，回答单次35秒（后台30秒加传输余量），均包含CSRF且不自动重试。 */
export function createEnterpriseClient(fetcher: typeof fetch = globalThis.fetch, timeoutMs = 10000) {
  const active = new Set<AbortController>();
  async function operation<T>(work: (request: (path: string, init?: RequestInit) => Promise<unknown>) => Promise<T>,
    create = false, durationMs = timeoutMs): Promise<T> {
    const controller = new AbortController();
    active.add(controller);
    let timedOut = false;
    let writeSent = false;
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, durationMs);
    const request = async (path: string, init: RequestInit = {}): Promise<unknown> => {
      if (create && init.method === 'POST') writeSent = true;
      const response = await fetcher(path, { ...init, credentials: 'same-origin', cache: 'no-store',
        redirect: 'error', signal: controller.signal });
      if (!response.ok) {
        const messages: Record<number, string> = {
          400: '输入格式不正确，请检查后重试。', 401: '请登录；账号或密码不正确、账号停用或登录已过期。',
          403: '请求校验未通过，请刷新页面并重新登录。', 404: '接口不存在，请确认已启动最新版后端。',
          413: '提交内容过大，请缩短后再试。', 429: '请求过于频繁，请稍后再试。'
        };
        throw new EnterpriseError(messages[response.status] ?? '企业服务暂不可用，请稍后手动重试。', response.status,
          create && writeSent && response.status >= 500);
      }
      if (response.headers.get('Content-Type')?.split(';')[0] === 'application/octet-stream') return response.blob();
      const payload: unknown = await response.json().catch(() => null);
      if (!object(payload) || !Number.isInteger(payload.code)) throw new EnterpriseError('服务数据没能正确读取，请稍后重试。', 0, create && writeSent);
      if (payload.code !== 0) {
        const messages: Record<number, string> = {
          400: '输入不符合要求，请检查名称、文件格式、有效期及操作参数。',
          401: '登录已失效，请重新登录。', 404: '资源不存在或不可访问；请核对企业编号及明确授权。',
          409: '记录已变化或尚不符合操作条件，请刷新空间、文档及版本状态后再操作。',
          413: '首批文字原件最大1MiB。', 503: '知识服务繁忙或暂不可用，请稍后手动重试。',
          504: '处理超过时限，本次未自动重试，请缩小问题或文件范围后再试。'
        };
        throw new EnterpriseError(messages[Number(payload.code)] ?? '操作未完成，请联系管理员或稍后重试。', Number(payload.code),
          create && writeSent && Number(payload.code) >= 500);
      }
      return payload.data;
    };
    try {
      return await work(request);
    } catch (error) {
      if (error instanceof EnterpriseError) {
        if (error.uncertain) error.message += ' 操作结果未知，请先刷新核对；上传须使用原请求编号，避免重复写入。';
        throw error;
      }
      const unknownWrite = create && writeSent;
      throw new EnterpriseError(unknownWrite
        ? '连接已结束，操作结果未知；后台可能已提交，请先刷新核对，上传须保留原请求编号。'
        : timedOut ? `等待超过${Math.ceil(durationMs / 1000)}秒，已停止连接；后台仍受时限约束，请勿连续重复提交。`
          : '连接已中断，请检查网络及后端服务。', 0, unknownWrite);
    } finally {
      clearTimeout(timer);
      active.delete(controller);
    }
  }
  type Request = Parameters<Parameters<typeof operation>[0]>[0];
  async function write(request: Request, path: string, body: string, contentType: string) {
    // 登录会轮换CSRF；每次写入重新获取，不复用登录前令牌，也不自动重试写请求。
    const csrf = await request('/api/auth/csrf');
    if (!object(csrf) || csrf.headerName !== 'X-CSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token)
      throw new EnterpriseError('请求凭据格式不正确，请刷新页面。');
    return request(path, { method: 'POST', headers: { 'Content-Type': contentType, [csrf.headerName]: csrf.token }, body });
  }
  /** 删除类请求：CSRF 与写请求一样每次重新获取，方法用 DELETE，不携带正文。 */
  async function remove(request: Request, path: string) {
    const csrf = await request('/api/auth/csrf');
    if (!object(csrf) || csrf.headerName !== 'X-CSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token)
      throw new EnterpriseError('请求凭据格式不正确，请刷新页面。');
    return request(path, { method: 'DELETE', headers: { [csrf.headerName]: csrf.token } });
  }
  async function upload(request: Request, path: string, metadata: UploadMetadata, file: File) {
    if (!/\.(txt|md|pdf|docx)$/i.test(file.name) || file.size === 0 || file.size > 1048576)
      throw new EnterpriseError('请选择1字节至1MiB的TXT/Markdown、文字层PDF或DOCX文件。');
    const csrf = await request('/api/auth/csrf');
    if (!object(csrf) || csrf.headerName !== 'X-CSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token)
      throw new EnterpriseError('请求凭据格式不正确，请刷新页面。');
    const form = new FormData();
    form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
    form.append('file', file);
    return request(path, { method: 'POST', headers: { [csrf.headerName]: csrf.token }, body: form });
  }
  /** 补贴凭证只收图片或PDF原件；不做OCR，也不在前端猜测内容类型。 */
  async function uploadReceipt(request: Request, path: string, file: File) {
    if (!/\.(jpg|jpeg|png|pdf)$/i.test(file.name) || file.size === 0 || file.size > 1048576)
      throw new EnterpriseError('请选择1字节至1MiB的JPG/PNG图片或PDF凭证。');
    const csrf = await request('/api/auth/csrf');
    if (!object(csrf) || csrf.headerName !== 'X-CSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token)
      throw new EnterpriseError('请求凭据格式不正确，请刷新页面。');
    const form = new FormData();
    form.append('file', file);
    return request(path, { method: 'POST', headers: { [csrf.headerName]: csrf.token }, body: form });
  }
  const base = (enterpriseId: string, spaceId: string) => `/api/enterprises/${id(enterpriseId)}/knowledge-spaces/${id(spaceId)}`;
  const docPath = (enterpriseId: string, spaceId: string, documentId: string) => `${base(enterpriseId, spaceId)}/documents/${id(documentId)}`;
  const affairBase = (enterpriseId: string) => `/api/enterprises/${id(enterpriseId)}/affairs`;
  const affairPath = (enterpriseId: string, applicationId: string) => `${affairBase(enterpriseId)}/applications/${uuid(applicationId)}`;
  /** 分金额只接受1至10亿的十进制字符串，避免浮点与科学计数法。 */
  function cents(value: string) {
    if (!/^(0|[1-9]\d{0,9})$/.test(value) || BigInt(value) < 1n || BigInt(value) > 1000000000n)
      throw new EnterpriseError('金额须为1至1000000000分的整数字符串。');
    return value;
  }
  function shareCents(value: string) {
    if (!/^(0|[1-9]\d{0,9})$/.test(value) || BigInt(value) > 1000000000n)
      throw new EnterpriseError('分摊金额须为不超过1000000000分的整数字符串。');
    return value;
  }
  /** 分摊只做格式与去重校验；合计等于金额与否由服务端判定。 */
  function sharesOf(participants: AffairShareInput[]) {
    if (!Array.isArray(participants) || participants.length === 0 || participants.length > 20)
      throw new EnterpriseError('分摊成员须为1至20人。');
    const seen = new Set<string>();
    return participants.map(item => {
      if (seen.has(item.memberId)) throw new EnterpriseError('分摊成员不能重复。');
      seen.add(item.memberId);
      return { memberId: id(item.memberId), shareCents: shareCents(item.shareCents) };
    });
  }
  const affairStatuses = ['SUBMITTED', 'CONFIRMING', 'VERIFIED', 'PENDING_SETTLEMENT', 'SETTLED', 'REJECTED', 'CANCELLED'];
  function filterStatus(status: string | null, allowed: string[], label: string) {
    if (status === null) return '';
    if (!allowed.includes(status)) throw new EnterpriseError(`${label}筛选值不正确。`);
    return `&status=${status}`;
  }
  async function jsonWrite(request: Request, path: string, value: unknown) {
    return write(request, path, JSON.stringify(value), 'application/json');
  }
  async function checkedWrite<T>(work: () => Promise<unknown>, parse: (value: unknown) => T): Promise<T> {
    const value = await work();
    try { return parse(value); }
    catch { throw new EnterpriseError('操作数据没能正确读取，请稍后重试。', 0, true); }
  }
  function id(value: string) {
    if (!positiveId(value)) throw new EnterpriseError('编号必须为正整数，且不能超过Java Long范围。');
    return value;
  }
  function page(value: number) {
    if (!Number.isSafeInteger(value) || value < 1) throw new EnterpriseError('页码须为正整数。');
    return value;
  }
  function uuid(value: string) {
    if (!workspaceUuid(value)) throw new EnterpriseError('会话和请求编号必须为UUID。');
    return value;
  }
  return {
    me: () => operation(async request => account(await request('/api/auth/me'))),
    login: (username: string, password: string) => operation(async request => {
      if (!username.trim() || !password) throw new EnterpriseError('请填写账号与密码。');
      await write(request, '/api/auth/login', new URLSearchParams({ username, password }).toString(), 'application/x-www-form-urlencoded');
      return account(await request('/api/auth/me'));
    }),
    logout: () => operation(async request => { await write(request, '/api/auth/logout', '', 'application/x-www-form-urlencoded'); }),
    changePassword: (previousPassword: string, newPassword: string) => operation(async request => {
      if (!previousPassword || previousPassword.length > 256 || newPassword.trim().length === 0
        || newPassword.length < 12 || new TextEncoder().encode(newPassword).length > 72 || previousPassword === newPassword)
        throw new EnterpriseError('新口令须至少12字符且不超过72字节，且与原口令不同。');
      try { await jsonWrite(request, '/api/auth/password', { previousPassword, newPassword }); }
      catch (error) {
        if (error instanceof EnterpriseError && error.code === 1000)
          throw new EnterpriseError('原口令不正确，密码未修改；请稍后核对再试。', 1000);
        throw error;
      }
    }, true),
    organizationDepartments: (enterprise: string, pageNum = 1) => operation(async request =>
      departmentsView(await request(`/api/enterprises/${id(enterprise)}/organization/departments?pageNum=${page(pageNum)}&pageSize=20`))),
    organizationMembers: (enterprise: string, pageNum = 1) => operation(async request =>
      membersView(await request(`/api/enterprises/${id(enterprise)}/organization/members?pageNum=${page(pageNum)}&pageSize=20`))),
    createDepartment: (enterprise: string, expected: number, name: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `/api/enterprises/${id(enterprise)}/organization/departments`,
        { expectedEnterpriseVersion: organizationVersion(expected), name: departmentName(name) }), departmentResult), true),
    changeDepartment: (enterprise: string, department: string, expected: number, name: string, status: number) => operation(request => {
      if (![0, 1].includes(status)) throw new EnterpriseError('部门状态不正确。');
      return checkedWrite(() => jsonWrite(request, `/api/enterprises/${id(enterprise)}/organization/departments/${id(department)}`,
        { expectedEnterpriseVersion: organizationVersion(expected), name: departmentName(name), status }), departmentResult);
    }, true),
    changeOrganizationMember: (enterprise: string, member: string, expected: number, departmentId: string | null, status: number) => operation(request => {
      if (![0, 1, 2].includes(status)) throw new EnterpriseError('成员状态不正确。');
      return checkedWrite(() => jsonWrite(request, `/api/enterprises/${id(enterprise)}/organization/members/${id(member)}`,
        { expectedVersion: organizationVersion(expected), departmentId: departmentId === null ? null : id(departmentId), status }), memberView);
    }, true),
    createEmployee: (enterprise: string, username: string, password: string) => operation(request => {
      if (!/^[A-Za-z0-9_.@-]{3,64}$/.test(username) || password.trim().length === 0 || password.length < 12 || new TextEncoder().encode(password).length > 72)
        throw new EnterpriseError('账号须为3至64位字母数字或_.@-；口令至少12字符且不超过72字节。');
      return checkedWrite(() => jsonWrite(request, `/api/enterprises/${id(enterprise)}/employees`, { username, password }), employeeResult);
    }, true),
    createSpace: (enterpriseId: string, name: string) => operation(async request => {
      const normalized = name.trim();
      if (!normalized || normalized.length > 128) throw new EnterpriseError('空间名称去除首尾空白后须为1至128个字符。');
      const data = await write(request, `/api/enterprises/${id(enterpriseId)}/knowledge-spaces`, JSON.stringify({ name: normalized }), 'application/json');
      try { return space(data); }
      catch { throw new EnterpriseError('空间数据没能正确读取，请稍后重试。', 0, true); }
    }, true),
    readSpace: (enterpriseId: string, spaceId: string) => operation(async request =>
      space(await request(`/api/enterprises/${id(enterpriseId)}/knowledge-spaces/${id(spaceId)}`))),
    listSpaces: (enterpriseId: string, pageNum = 1, pageSize = 20) => operation(async request => {
      if (!Number.isSafeInteger(pageNum) || pageNum < 1 || !Number.isSafeInteger(pageSize) || pageSize < 1 || pageSize > 500)
        throw new EnterpriseError('页码须为正整数，每页条数须为1至500。');
      return spacePage(await request(`/api/enterprises/${id(enterpriseId)}/knowledge-spaces?pageNum=${pageNum}&pageSize=${pageSize}`));
    }),
    renameSpace: (enterpriseId: string, spaceId: string, expectedVersion: number, name: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${base(enterpriseId, spaceId)}/rename`, { expectedVersion, name }), space), true),
    grants: (enterpriseId: string, spaceId: string) => operation(async request => grantsView(await request(`${base(enterpriseId, spaceId)}/grants`))),
    changeGrant: (enterpriseId: string, spaceId: string, expectedVersion: number, targetType: string, targetId: string, permission: string, revoke = false) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${base(enterpriseId, spaceId)}/grants`, { expectedVersion, targetType, targetId: id(targetId), permission, revoke }), space), true),
    documents: (enterpriseId: string, spaceId: string, pageNum = 1) => operation(async request =>
      pageView(await request(`${base(enterpriseId, spaceId)}/documents?pageNum=${page(pageNum)}&pageSize=20`), documentView)),
    document: (enterpriseId: string, spaceId: string, documentId: string) => operation(async request =>
      documentView(await request(docPath(enterpriseId, spaceId, documentId)))),
    versions: (enterpriseId: string, spaceId: string, documentId: string, pageNum = 1) => operation(async request =>
      pageView(await request(`${docPath(enterpriseId, spaceId, documentId)}/versions?pageNum=${page(pageNum)}&pageSize=20`), versionView)),
    chunks: (enterpriseId: string, spaceId: string, documentId: string, versionId: string, pageNum = 1) => operation(async request =>
      pageView(await request(`${docPath(enterpriseId, spaceId, documentId)}/versions/${id(versionId)}/chunks?pageNum=${page(pageNum)}&pageSize=20`), chunkView)),
    uploadDocument: (enterpriseId: string, spaceId: string, metadata: UploadMetadata, file: File) => operation(request =>
      checkedWrite(() => upload(request, `${base(enterpriseId, spaceId)}/documents`, metadata, file), versionView), true),
    parseVersion: (enterpriseId: string, spaceId: string, documentId: string, versionId: string, expectedRevision: number) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${docPath(enterpriseId, spaceId, documentId)}/versions/${id(versionId)}/parse`, { expectedRevision }), versionView), true),
    submitIngest: (enterpriseId: string, spaceId: string, documentId: string, versionId: string, requestId: string, expectedRevision: number, action: string) => operation(async request => {
      if (!Number.isSafeInteger(expectedRevision) || expectedRevision < 1 || !['PARSE', 'REBUILD'].includes(action)) throw new EnterpriseError('入库动作或修订号不正确。');
      return checkedWrite(() => jsonWrite(request, `${docPath(enterpriseId, spaceId, documentId)}/versions/${id(versionId)}/ingest-jobs`,
        { requestId: uuid(requestId), expectedRevision, action }), ingestJob);
    }, true),
    ingestHistory: (enterpriseId: string, spaceId: string, documentId: string, versionId: string) => operation(async request =>
      ingestJobs(await request(`${docPath(enterpriseId, spaceId, documentId)}/versions/${id(versionId)}/ingest-jobs`))),
    readIngest: (enterpriseId: string, spaceId: string, documentId: string, versionId: string, requestId: string) => operation(async request =>
      ingestJob(await request(`${docPath(enterpriseId, spaceId, documentId)}/versions/${id(versionId)}/ingest-jobs/${uuid(requestId)}`))),
    cancelIngest: (enterpriseId: string, spaceId: string, documentId: string, versionId: string, requestId: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${docPath(enterpriseId, spaceId, documentId)}/versions/${id(versionId)}/ingest-jobs/${uuid(requestId)}/cancel`, {}), ingestJob), true),
    submitVectorJob: (enterprise: string, space: string, document: string, version: string, requestId: string, expectedRevision: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${docPath(enterprise, space, document)}/versions/${id(version)}/vector-jobs`,
        { requestId: uuid(requestId), expectedRevision: id(expectedRevision) }),
      value => vectorJob(value, document, version, requestId, expectedRevision)), true),
    readVectorJob: (enterprise: string, space: string, document: string, version: string, requestId: string) => operation(async request =>
      vectorJob(await request(`${docPath(enterprise, space, document)}/versions/${id(version)}/vector-jobs/${uuid(requestId)}`), document, version, requestId)),
    cancelVectorJob: (enterprise: string, space: string, document: string, version: string, requestId: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${docPath(enterprise, space, document)}/versions/${id(version)}/vector-jobs/${uuid(requestId)}/cancel`, {}),
        value => vectorJob(value, document, version, requestId)), true),
    readVectorPublication: (enterprise: string, space: string, document: string) => operation(async request =>
      vectorPublication(await request(`${docPath(enterprise, space, document)}/vector-publication`), document)),
    publishVectorIndex: (enterprise: string, space: string, document: string, jobId: string, expectedRevision: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${docPath(enterprise, space, document)}/vector-publication/publish`,
        { jobId: uuid(jobId), expectedRevision: pointerRevision(expectedRevision) }), value => {
          const publication = vectorPublication(value, document);
          if (publication.status !== 'READY' || publication.jobId !== jobId
            || publication.revision !== (BigInt(expectedRevision) + 1n).toString()) return badVector();
          return publication;
        }), true),
    withdrawVectorIndex: (enterprise: string, space: string, document: string, expectedJobId: string | null, expectedRevision: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${docPath(enterprise, space, document)}/vector-publication/withdraw`,
        { expectedJobId: expectedJobId === null ? null : uuid(expectedJobId), expectedRevision: pointerRevision(expectedRevision) }), value => {
          const publication = vectorPublication(value, document);
          if (publication.status !== 'WITHDRAWN' || publication.revision !== (BigInt(expectedRevision) + 1n).toString()) return badVector();
          return publication;
        }), true),
    lifecycle: (enterpriseId: string, spaceId: string, documentId: string, versionId: string | null, expectedRevision: number, action: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${docPath(enterpriseId, spaceId, documentId)}/lifecycle`, { versionId, expectedRevision, action }), documentView), true),
    assignOwner: (enterpriseId: string, spaceId: string, documentId: string, expectedRevision: number, ownerMemberId: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${docPath(enterpriseId, spaceId, documentId)}/owner`, { expectedRevision, ownerMemberId: id(ownerMemberId) }), documentView), true),
    original: (enterpriseId: string, spaceId: string, documentId: string, versionId: string) => operation(async request => {
      const value = await request(`${docPath(enterpriseId, spaceId, documentId)}/versions/${id(versionId)}/original`);
      if (!(value instanceof Blob) || value.size === 0 || value.size > 1048576) throw new EnterpriseError('原件响应格式或大小不正确。');
      return value;
    }),
    answerMode: (enterpriseId: string, spaceId: string) => operation(async request => {
      const mode = await request(`${base(enterpriseId, spaceId)}/answers/mode`);
      if (mode !== 'SYNTHETIC_MODEL' && mode !== 'LOCAL_EVIDENCE') throw new EnterpriseError('回答模式响应不正确。');
      return mode;
    }),
    retrievalMode: (enterpriseId: string, spaceId: string) => operation(async request => {
      const mode = await request(`${base(enterpriseId, spaceId)}/answers/retrieval-mode`);
      if (mode !== 'KEYWORD' && mode !== 'HYBRID') throw new EnterpriseError('检索策略响应不正确。');
      return mode;
    }),
    answer: (enterpriseId: string, spaceId: string, question: string) => operation(async request => {
      if (!question.trim() || question.length > 1200) throw new EnterpriseError('问题须为1至1200个字符。');
      const data = await jsonWrite(request, `${base(enterpriseId, spaceId)}/answers`, { question });
      try { return answerView(data); }
      catch { throw new EnterpriseError('回答数据格式不正确，本次不会自动重新调用模型。'); }
    }, false, Math.max(timeoutMs, 35000)),
    evidence: (enterpriseId: string, spaceId: string, chunkId: string) => operation(async request => {
      const data = await request(`${base(enterpriseId, spaceId)}/answers/evidence/${id(chunkId)}`);
      try { return evidenceView(data); }
      catch { throw new EnterpriseError('引用数据格式不正确，请联系知识负责人。'); }
    }),
    chats: (enterpriseId: string, spaceId: string) => operation(async request =>
      workspaceChats(await request(`${base(enterpriseId, spaceId)}/workspace/chats`))),
    /** 删除本人会话：后端是软删除，任务与反馈留作审计，重复删除同样成功。 */
    deleteChat: (enterpriseId: string, spaceId: string, chatId: string) => operation(request =>
      remove(request, `${base(enterpriseId, spaceId)}/workspace/chats/${uuid(chatId)}`), true),
    chatHistory: (enterpriseId: string, spaceId: string, chatId: string) => operation(async request =>
      workspaceHistory(await request(`${base(enterpriseId, spaceId)}/workspace/chats/${uuid(chatId)}`))),
    submitJob: (enterpriseId: string, spaceId: string, chatId: string, requestId: string, question: string) => operation(async request => {
      if (!question.trim() || question.length > 1200) throw new EnterpriseError('问题须为1至1200个字符。');
      return checkedWrite(() => jsonWrite(request, `${base(enterpriseId, spaceId)}/workspace/jobs`,
        { chatId: uuid(chatId), requestId: uuid(requestId), question }), workspaceJob);
    }, true),
    readJob: (enterpriseId: string, spaceId: string, requestId: string) => operation(async request =>
      workspaceJob(await request(`${base(enterpriseId, spaceId)}/workspace/jobs/${uuid(requestId)}`))),
    cancelJob: (enterpriseId: string, spaceId: string, requestId: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${base(enterpriseId, spaceId)}/workspace/jobs/${uuid(requestId)}/cancel`, {}), workspaceJob), true),
    sendFeedback: (enterpriseId: string, spaceId: string, value: { id: string; jobId: string; chunkId: string | null; category: string; comment: string }) => operation(async request => {
      uuid(value.id); uuid(value.jobId);
      if (!value.comment.trim() || value.comment.length > 1000) throw new EnterpriseError('反馈说明须为1至1000字符。');
      const data = await jsonWrite(request, `${base(enterpriseId, spaceId)}/workspace/feedback`, value);
      return feedbackList([data])[0]!;
    }, true),
    feedbacks: (enterpriseId: string, spaceId: string) => operation(async request =>
      feedbackList(await request(`${base(enterpriseId, spaceId)}/workspace/feedback`))),
    resolveFeedback: (enterpriseId: string, spaceId: string, feedbackId: string, revision: number, status: string, resolution: string) => operation(async request => {
      if (!resolution.trim() || resolution.length > 1000 || !Number.isSafeInteger(revision) || revision < 1
        || !['RESOLVED', 'DISMISSED'].includes(status)) throw new EnterpriseError('处理状态、版本或说明不正确。');
      await jsonWrite(request, `${base(enterpriseId, spaceId)}/workspace/feedback/${uuid(feedbackId)}/resolve`, { revision, status, resolution });
    }, true),
    /** 补贴规则：员工只读ACTIVE；管理员与财务可按状态筛选。 */
    affairsRules: (enterprise: string, status: string | null = null, pageNum = 1) => operation(async request =>
      affairsPage(await request(`${affairBase(enterprise)}/rules?pageNum=${page(pageNum)}&pageSize=20${filterStatus(status, ['DRAFT', 'ACTIVE', 'RETIRED'], '规则状态')}`), ruleView, '补贴规则')),
    affairMemberOptions: (enterprise: string) => operation(async request =>
      memberList(await request(`${affairBase(enterprise)}/members`))),
    affairEligibility: (enterprise: string, ruleId: string, amountCents: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${affairBase(enterprise)}/eligibility`,
        { ruleId: id(ruleId), amountCents: cents(amountCents) }), eligibilityView)),
    createAffairRule: (enterprise: string, value: { name: string; period: 'MONTH' | 'QUARTER' | 'YEAR' | 'NONE'; memberLimitCents: string | null; applicationLimitCents: string; requiresManager: boolean }) => operation(request => {
      if (!value.name.trim() || value.name.length > 128) throw new EnterpriseError('规则名称须为1至128个字符。');
      if (!['MONTH', 'QUARTER', 'YEAR', 'NONE'].includes(value.period)) throw new EnterpriseError('额度周期不正确。');
      return checkedWrite(() => jsonWrite(request, `${affairBase(enterprise)}/rules`, { name: value.name.trim(), period: value.period,
        memberLimitCents: value.memberLimitCents === null ? null : cents(value.memberLimitCents),
        applicationLimitCents: cents(value.applicationLimitCents), requiresManager: value.requiresManager }), ruleView);
    }, true),
    activateAffairRule: (enterprise: string, ruleId: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${affairBase(enterprise)}/rules/${id(ruleId)}/activate`, {}), ruleView), true),
    /** 提交申请：客户端UUID保证重复点击幂等；分摊合计必须等于金额，由服务端复核。 */
    createAffairApplication: (enterprise: string, value: { id: string; ruleId: string; platform: string; externalOrderNo: string | null; occurredAt: string | null; amountCents: string; participants: AffairShareInput[] }) => operation(request => {
      const channel = value.platform.trim();
      if (!/^[A-Za-z0-9_-]{1,32}$/.test(channel)) throw new EnterpriseError('平台标识须为1至32位字母、数字或_-。');
      if (value.externalOrderNo !== null && (!value.externalOrderNo.trim() || value.externalOrderNo.length > 64))
        throw new EnterpriseError('外部订单号须为1至64个字符。');
      if (value.occurredAt !== null && !/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value.occurredAt))
        throw new EnterpriseError('发生时间格式须为yyyy-MM-dd HH:mm:ss。');
      return checkedWrite(() => jsonWrite(request, `${affairBase(enterprise)}/applications`, {
        id: uuid(value.id), ruleId: id(value.ruleId), platform: channel,
        externalOrderNo: value.externalOrderNo === null ? null : value.externalOrderNo.trim(),
        occurredAt: value.occurredAt, amountCents: cents(value.amountCents), participants: sharesOf(value.participants)
      }), applicationView);
    }, true),
    affairApplications: (enterprise: string, status: string | null = null, pageNum = 1) => operation(async request =>
      affairsPage(await request(`${affairBase(enterprise)}/applications?pageNum=${page(pageNum)}&pageSize=20${filterStatus(status, affairStatuses, '申请状态')}`), summaryRow, '我的申请')),
    affairApplication: (enterprise: string, applicationId: string) => operation(async request =>
      applicationView(await request(affairPath(enterprise, applicationId)))),
    reviseAffairApplication: (enterprise: string, applicationId: string, expectedVersion: number, amountCents: string, participants: AffairShareInput[], reason: string) => operation(request => {
      if (!Number.isSafeInteger(expectedVersion) || expectedVersion < 1) throw new EnterpriseError('申请版本号须为正整数。');
      if (!reason.trim() || reason.length > 200) throw new EnterpriseError('修改原因须为1至200个字符。');
      return checkedWrite(() => jsonWrite(request, `${affairPath(enterprise, applicationId)}/revise`, {
        expectedVersion, amountCents: cents(amountCents), participants: sharesOf(participants), reason: reason.trim()
      }), applicationView);
    }, true),
    cancelAffairApplication: (enterprise: string, applicationId: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${affairPath(enterprise, applicationId)}/cancel`, {}), applicationView), true),
    confirmAffairApplication: (enterprise: string, applicationId: string, version: number, decision: 'CONFIRM' | 'REJECT', note: string | null) => operation(request => {
      if (!Number.isSafeInteger(version) || version < 1) throw new EnterpriseError('确认版本号不正确。');
      if (!['CONFIRM', 'REJECT'].includes(decision)) throw new EnterpriseError('确认结论不正确。');
      if (note !== null && (!note.trim() || note.length > 200)) throw new EnterpriseError('确认说明须为1至200个字符。');
      return checkedWrite(() => jsonWrite(request, `${affairPath(enterprise, applicationId)}/confirm`,
        { version, decision, note: note === null ? null : note.trim() }), applicationView);
    }, true),
    affairConfirmations: (enterprise: string, pageNum = 1) => operation(async request =>
      affairsPage(await request(`${affairBase(enterprise)}/confirmations?pageNum=${page(pageNum)}&pageSize=20`), pendingRow, '待我确认')),
    affairReceipts: (enterprise: string, applicationId: string, pageNum = 1) => operation(async request =>
      affairsPage(await request(`${affairPath(enterprise, applicationId)}/receipts?pageNum=${page(pageNum)}&pageSize=20`), receiptView, '补贴凭证')),
    uploadAffairReceipt: (enterprise: string, applicationId: string, file: File) => operation(request =>
      checkedWrite(() => uploadReceipt(request, `${affairPath(enterprise, applicationId)}/receipts`, file), receiptView), true),
    downloadAffairReceipt: (enterprise: string, applicationId: string, receiptId: string) => operation(async request => {
      const value = await request(`${affairPath(enterprise, applicationId)}/receipts/${id(receiptId)}/download`);
      if (!(value instanceof Blob) || value.size === 0 || value.size > 1048576)
        throw new EnterpriseError('凭证下载响应格式或大小不正确。');
      return value;
    }),
    approveAffairApplication: (enterprise: string, applicationId: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${affairPath(enterprise, applicationId)}/manager-approve`, {}), value => {
        if (!object(value) || value.id !== applicationId || value.status !== 'PENDING_SETTLEMENT')
          throw new EnterpriseError('主管批准数据没能正确读取，请稍后重试。');
        return { id: applicationId, status: 'PENDING_SETTLEMENT' as const };
      }), true),
    affairFinanceApplications: (enterprise: string, status: string | null = null, pageNum = 1) => operation(async request =>
      affairsPage(await request(`${affairBase(enterprise)}/finance/applications?pageNum=${page(pageNum)}&pageSize=20${filterStatus(status, affairStatuses, '财务状态')}`), financeRow, '财务清单')),
    affairSettlements: (enterprise: string, status: string | null = null, pageNum = 1) => operation(async request =>
      affairsPage(await request(`${affairBase(enterprise)}/settlements?pageNum=${page(pageNum)}&pageSize=20${filterStatus(status, ['PENDING', 'APPROVED', 'SETTLED', 'RETURNED', 'VOID'], '结算状态')}`), settlementRow, '结算清单')),
    createAffairSettlement: (enterprise: string, applicationId: string, payeeMemberId: string, note: string | null) => operation(request => {
      if (note !== null && note.length > 500) throw new EnterpriseError('核算说明不能超过500个字符。');
      return checkedWrite(() => jsonWrite(request, `${affairBase(enterprise)}/settlements`, { applicationId: uuid(applicationId),
        payeeMemberId: id(payeeMemberId), note: note === null || !note.trim() ? null : note.trim() }), settlementView);
    }, true),
    approveAffairSettlement: (enterprise: string, settlementId: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${affairBase(enterprise)}/settlements/${id(settlementId)}/approve`, {}), settlementView), true),
    settleAffairSettlement: (enterprise: string, settlementId: string) => operation(request =>
      checkedWrite(() => jsonWrite(request, `${affairBase(enterprise)}/settlements/${id(settlementId)}/settle`, {}), settlementView), true),
    refundAffairApplication: (enterprise: string, applicationId: string, kind: 'FULL' | 'PARTIAL', amountCents: string, reason: string) => operation(request => {
      if (!['FULL', 'PARTIAL'].includes(kind)) throw new EnterpriseError('退款类型不正确。');
      if (!reason.trim() || reason.length > 200) throw new EnterpriseError('退款原因须为1至200个字符。');
      return checkedWrite(() => jsonWrite(request, `${affairPath(enterprise, applicationId)}/refund`,
        { kind, amountCents: cents(amountCents), reason: reason.trim() }), refundView);
    }, true),
    dispose: () => { for (const controller of active) controller.abort(); }
  };
}
