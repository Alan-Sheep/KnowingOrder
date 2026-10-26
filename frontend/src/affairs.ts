/** 员工事务响应校验：金额一律用十进制字符串承接 BIGINT，避免经过 Number 丢精度。 */
import { EnterpriseError } from './enterprise';

export type AffairStatus = 'SUBMITTED' | 'CONFIRMING' | 'VERIFIED' | 'PENDING_SETTLEMENT' | 'SETTLED' | 'REJECTED' | 'CANCELLED';
export interface AffairsRule {
  id: string; name: string; version: number; status: 'DRAFT' | 'ACTIVE' | 'RETIRED';
  period: 'MONTH' | 'QUARTER' | 'YEAR' | 'NONE'; memberLimitCents: string | null;
  applicationLimitCents: string; requiresManager: boolean; effectiveFrom: string; effectiveTo: string | null;
}
export interface AffairEligibility {
  period: string; limitCents: string | null; usedCents: string; reservedCents: string; remainingCents: string | null;
  applicationLimitCents: string; maxApplicableCents: string; applicableCents: string;
  requiresManager: boolean; ruleId: string; ruleVersion: number; ruleName: string;
}
export interface AffairMember { memberId: string; username: string }
export interface AffairParticipant {
  id: string; memberId: string; username: string; shareCents: string;
  status: 'PENDING' | 'CONFIRMED' | 'REJECTED'; confirmedMs: number | null; note: string | null;
}
export interface AffairApplication {
  id: string; ruleId: string; ruleVersion: number; ruleName: string; platform: string; externalOrderNo: string | null;
  occurredAt: string; amountCents: string; status: AffairStatus; currentVersion: number; quotaPeriod: string;
  reservedCents: string; requiresManager: boolean; applicantMemberId: string; payerMemberId: string;
  payeeMemberId: string | null; createdMs: number; participants: AffairParticipant[];
}
export interface AffairSummary {
  id: string; ruleName: string; platform: string; occurredAt: string; amountCents: string;
  status: AffairStatus; currentVersion: number; createdMs: number;
}
export interface AffairPending {
  participantId: string; applicationId: string; ruleName: string; platform: string; occurredAt: string;
  amountCents: string; version: number; shareCents: string; createdMs: number;
}
export interface AffairReceipt {
  id: string; originalName: string; contentType: string; byteSize: number; sha256: string;
  createdMs: number; uploadedBy: string; uploaderName: string;
}
export interface AffairFinanceApplication {
  id: string; ruleName: string; applicantName: string; amountCents: string; status: AffairStatus;
  occurredAt: string; createdMs: number; settlementId: string | null;
  settlementStatus: 'PENDING' | 'APPROVED' | 'SETTLED' | 'RETURNED' | 'VOID' | null;
}
export interface AffairSettlement {
  id: string; applicationId: string; ruleName: string; applicantName: string; amountCents: string;
  payeeName: string; status: 'PENDING' | 'APPROVED' | 'SETTLED' | 'RETURNED' | 'VOID'; createdMs: number;
}
export interface AffairSettlementView {
  id: string; applicationId: string; ruleName: string; amountCents: string; payeeMemberId: string; payeeName: string;
  status: 'PENDING' | 'APPROVED' | 'SETTLED' | 'RETURNED' | 'VOID'; note: string | null;
  createdMs: number; approvedMs: number | null; settledMs: number | null;
}
export interface AffairRefund {
  applicationId: string; applicationStatus: AffairStatus; kind: 'REFUND_FULL' | 'REFUND_PARTIAL';
  amountCents: string; settlementId: string | null; settlementStatus: string | null;
}
export interface AffairsPage<T> { records: T[]; total: number; pageNum: number; pageSize: number; pages: number }

const object = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
/**
 * 金额规范化。后端约定金额用十进制字符串传输，但早期 DTO 用了原始类型 long，
 * Jackson 只对包装类 Long 注册了 ToStringSerializer，实际会输出 JSON 数字。
 * 这里同时接受两种形态并统一成字符串，避免单个字段类型不一致就让整页数据全部失联；
 * 数字分支限制在安全整数范围内，超出说明后端已超出可安全表达的额度，应当直接报错。
 */
const amount = (value: unknown): string | null => {
  if (typeof value === 'string') {
    return /^(0|[1-9]\d{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n ? value : null;
  }
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return String(value);
  return null;
};
const money = (value: unknown): boolean => amount(value) !== null;
/**
 * 编号归一化。与金额同理：后端 Long 输出字符串，原始类型 long 输出数字。
 * 统一成十进制字符串，前端后续比较与拼路径都按同一形态处理。
 */
const ref = (value: unknown): string | null => {
  if (typeof value === 'string') {
    return /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n ? value : null;
  }
  if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return String(value);
  return null;
};
const idOk = (value: unknown): boolean => ref(value) !== null;
/** 允许为空的金额字段：null 保持 null，其余按 amount 规范化。 */
const optionalAmount = (value: unknown): string | null => (value === null ? null : amount(value));
const text = (value: unknown, max: number): value is string =>
  typeof value === 'string' && !!value.trim() && value.length <= max;
/**
 * 毫秒时间戳。包装类 Long 会被 Jackson 序列化成字符串（与金额同理），
 * 这里同时接受数字与十进制字符串并统一成 number，避免单个字段形态不一致
 * 让「批准/标记结算」这类操作明明成功却被解析层判失败。
 */
const ms = (value: unknown): number | null => {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value >= 0 ? value : null;
  if (typeof value === 'string' && /^\d+$/.test(value)) {
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null;
  }
  return null;
};
const milli = (value: unknown): boolean => ms(value) !== null;
const time = (value: unknown): value is string =>
  typeof value === 'string' && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value);
const uuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
const statuses: AffairStatus[] = ['SUBMITTED', 'CONFIRMING', 'VERIFIED', 'PENDING_SETTLEMENT', 'SETTLED', 'REJECTED', 'CANCELLED'];
const settlementStatuses = ['PENDING', 'APPROVED', 'SETTLED', 'RETURNED', 'VOID'];
const fail = (label: string): never => { throw new EnterpriseError(`${label}数据没能正确读取，请稍后重试；若持续出现，请联系管理员。`); };

export function affairsPage<T>(value: unknown, row: (item: unknown) => T, label: string): AffairsPage<T> {
  if (!object(value) || !Array.isArray(value.records)
    || !['total', 'pageNum', 'pageSize', 'pages'].every(key => Number.isSafeInteger(value[key]))
    || Number(value.total) < 0 || Number(value.pageNum) < 1 || Number(value.pageSize) < 1
    || Number(value.pageSize) > 100 || Number(value.pages) < 0 || value.records.length > Number(value.pageSize)) return fail(`${label}列表`);
  return { records: value.records.map(row), total: Number(value.total), pageNum: Number(value.pageNum),
    pageSize: Number(value.pageSize), pages: Number(value.pages) };
}

export function affairRule(value: unknown): AffairsRule {
  if (!object(value) || !idOk(value.id) || !text(value.name, 128)
    || !Number.isInteger(value.version) || Number(value.version) < 1
    || !['DRAFT', 'ACTIVE', 'RETIRED'].includes(String(value.status))
    || !['MONTH', 'QUARTER', 'YEAR', 'NONE'].includes(String(value.period))
    || !(value.memberLimitCents === null || money(value.memberLimitCents))
    || !money(value.applicationLimitCents)
    || ![0, 1, true, false].includes(value.requiresManager as number | boolean)
    || !time(value.effectiveFrom) || !(value.effectiveTo === null || time(value.effectiveTo))) return fail('补贴规则');
  return { id: ref(value.id)!, name: value.name, version: Number(value.version), status: value.status as AffairsRule['status'],
    period: value.period as AffairsRule['period'], memberLimitCents: optionalAmount(value.memberLimitCents),
    applicationLimitCents: amount(value.applicationLimitCents)!, requiresManager: Boolean(value.requiresManager),
    effectiveFrom: value.effectiveFrom, effectiveTo: value.effectiveTo as string | null };
}

export function affairEligibility(value: unknown): AffairEligibility {
  if (!object(value) || !text(value.period, 8) || !(value.limitCents === null || money(value.limitCents))
    || !money(value.usedCents) || !money(value.reservedCents) || !(value.remainingCents === null || money(value.remainingCents))
    || !money(value.applicationLimitCents) || !money(value.maxApplicableCents) || !money(value.applicableCents)
    || typeof value.requiresManager !== 'boolean' || !idOk(value.ruleId)
    || !Number.isInteger(value.ruleVersion) || !text(value.ruleName, 128)) return fail('资格预览');
  return { period: value.period, limitCents: optionalAmount(value.limitCents), usedCents: amount(value.usedCents)!,
    reservedCents: amount(value.reservedCents)!, remainingCents: optionalAmount(value.remainingCents),
    applicationLimitCents: amount(value.applicationLimitCents)!, maxApplicableCents: amount(value.maxApplicableCents)!,
    applicableCents: amount(value.applicableCents)!, requiresManager: value.requiresManager, ruleId: ref(value.ruleId)!,
    ruleVersion: Number(value.ruleVersion), ruleName: value.ruleName };
}

export function affairMembers(value: unknown): AffairMember[] {
  if (!Array.isArray(value) || value.length > 500) return fail('成员目录');
  return value.map(item => {
    if (!object(item) || !idOk(item.memberId) || !text(item.username, 128)) return fail('成员目录');
    return { memberId: ref(item.memberId)!, username: item.username };
  });
}

export function affairParticipant(value: unknown): AffairParticipant {
  if (!object(value) || !idOk(value.id) || !idOk(value.memberId) || !text(value.username, 128)
    || !money(value.shareCents) || !['PENDING', 'CONFIRMED', 'REJECTED'].includes(String(value.status))
    || !(value.confirmedMs === null || milli(value.confirmedMs)) || !(value.note === null || text(value.note, 200)))
    return fail('参与确认');
  return { id: ref(value.id)!, memberId: ref(value.memberId)!, username: value.username, shareCents: amount(value.shareCents)!,
    status: value.status as AffairParticipant['status'], confirmedMs: ms(value.confirmedMs),
    note: value.note as string | null };
}

export function affairApplication(value: unknown): AffairApplication {
  if (!object(value) || !uuid(value.id) || !idOk(value.ruleId) || !Number.isInteger(value.ruleVersion)
    || !text(value.ruleName, 128) || !text(value.platform, 32)
    || !(value.externalOrderNo === null || text(value.externalOrderNo, 64)) || !time(value.occurredAt)
    || !money(value.amountCents) || !statuses.includes(value.status as AffairStatus)
    || !Number.isInteger(value.currentVersion) || Number(value.currentVersion) < 1 || !text(value.quotaPeriod, 8)
    || !money(value.reservedCents) || typeof value.requiresManager !== 'boolean'
    || !idOk(value.applicantMemberId) || !idOk(value.payerMemberId)
    || !(value.payeeMemberId === null || idOk(value.payeeMemberId)) || !milli(value.createdMs)
    || !Array.isArray(value.participants) || value.participants.length > 20) return fail('补贴申请');
  // id 是 UUID：校验用 uuid()，输出必须原样保留；ref() 只归一化纯数字编号，
  // 传 UUID 会得到 null，导致下拉选项丢掉 value（表现为「会话和请求编号必须为UUID」）。
  return { id: value.id as string, ruleId: ref(value.ruleId)!, ruleVersion: Number(value.ruleVersion), ruleName: value.ruleName,
    platform: value.platform, externalOrderNo: value.externalOrderNo as string | null, occurredAt: value.occurredAt,
    amountCents: amount(value.amountCents)!, status: value.status as AffairStatus, currentVersion: Number(value.currentVersion),
    quotaPeriod: value.quotaPeriod, reservedCents: amount(value.reservedCents)!, requiresManager: value.requiresManager,
    applicantMemberId: ref(value.applicantMemberId)!, payerMemberId: ref(value.payerMemberId)!,
    payeeMemberId: ref(value.payeeMemberId)! as string | null, createdMs: ms(value.createdMs)!,
    participants: value.participants.map(affairParticipant) };
}

export function affairSummary(value: unknown): AffairSummary {
  if (!object(value) || !uuid(value.id) || !text(value.ruleName, 128) || !text(value.platform, 32)
    || !time(value.occurredAt) || !money(value.amountCents) || !statuses.includes(value.status as AffairStatus)
    || !Number.isInteger(value.currentVersion) || !milli(value.createdMs)) return fail('我的申请');
  return { id: value.id as string, ruleName: value.ruleName, platform: value.platform, occurredAt: value.occurredAt,
    amountCents: amount(value.amountCents)!, status: value.status as AffairStatus,
    currentVersion: Number(value.currentVersion), createdMs: ms(value.createdMs)! };
}

export function affairPending(value: unknown): AffairPending {
  if (!object(value) || !idOk(value.participantId) || !uuid(value.applicationId) || !text(value.ruleName, 128)
    || !text(value.platform, 32) || !time(value.occurredAt) || !money(value.amountCents)
    || !Number.isInteger(value.version) || !money(value.shareCents) || !milli(value.createdMs)) return fail('待确认');
  return { participantId: ref(value.participantId)!, applicationId: value.applicationId, ruleName: value.ruleName,
    platform: value.platform, occurredAt: value.occurredAt, amountCents: amount(value.amountCents)!,
    version: Number(value.version), shareCents: amount(value.shareCents)!, createdMs: ms(value.createdMs)! };
}

export function affairReceipt(value: unknown): AffairReceipt {
  if (!object(value) || !idOk(value.id) || !text(value.originalName, 128) || !text(value.contentType, 64)
    || !Number.isInteger(value.byteSize) || Number(value.byteSize) < 1 || Number(value.byteSize) > 1048576
    || typeof value.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(value.sha256) || !milli(value.createdMs)
    || !idOk(value.uploadedBy) || !text(value.uploaderName, 128)) return fail('凭证');
  return { id: ref(value.id)!, originalName: value.originalName, contentType: value.contentType, byteSize: Number(value.byteSize),
    sha256: value.sha256, createdMs: ms(value.createdMs)!, uploadedBy: ref(value.uploadedBy)!, uploaderName: value.uploaderName };
}

export function affairFinance(value: unknown): AffairFinanceApplication {
  if (!object(value) || !uuid(value.id) || !text(value.ruleName, 128) || !text(value.applicantName, 128)
    || !money(value.amountCents) || !statuses.includes(value.status as AffairStatus) || !time(value.occurredAt)
    || !milli(value.createdMs) || !(value.settlementId === null || idOk(value.settlementId))
    || !(value.settlementStatus === null || settlementStatuses.includes(String(value.settlementStatus))))
    return fail('财务清单');
  // id 是 UUID：同 affairApplication，不能用 ref() 归一化。
  return { id: value.id as string, ruleName: value.ruleName, applicantName: value.applicantName, amountCents: amount(value.amountCents)!,
    status: value.status as AffairStatus, occurredAt: value.occurredAt, createdMs: ms(value.createdMs)!,
    settlementId: value.settlementId as string | null, settlementStatus: value.settlementStatus as AffairFinanceApplication['settlementStatus'] };
}

export function affairSettlement(value: unknown): AffairSettlement {
  if (!object(value) || !idOk(value.id) || !uuid(value.applicationId) || !text(value.ruleName, 128)
    || !text(value.applicantName, 128) || !money(value.amountCents) || !text(value.payeeName, 128)
    || !settlementStatuses.includes(String(value.status)) || !milli(value.createdMs)) return fail('结算单');
  return { id: ref(value.id)!, applicationId: value.applicationId, ruleName: value.ruleName,
    applicantName: value.applicantName, amountCents: amount(value.amountCents)!, payeeName: value.payeeName,
    status: value.status as AffairSettlement['status'], createdMs: ms(value.createdMs)! };
}

export function affairSettlementView(value: unknown): AffairSettlementView {
  if (!object(value) || !idOk(value.id) || !uuid(value.applicationId) || !text(value.ruleName, 128)
    || !money(value.amountCents) || !idOk(value.payeeMemberId) || !text(value.payeeName, 128)
    || !settlementStatuses.includes(String(value.status)) || !(value.note === null || text(value.note, 500))
    || !milli(value.createdMs) || !(value.approvedMs === null || milli(value.approvedMs))
    || !(value.settledMs === null || milli(value.settledMs))) return fail('结算单');
  return { id: ref(value.id)!, applicationId: value.applicationId, ruleName: value.ruleName, amountCents: amount(value.amountCents)!,
    payeeMemberId: ref(value.payeeMemberId)!, payeeName: value.payeeName, status: value.status as AffairSettlementView['status'],
    note: value.note as string | null, createdMs: ms(value.createdMs)!, approvedMs: ms(value.approvedMs),
    settledMs: ms(value.settledMs) };
}

export function affairRefund(value: unknown): AffairRefund {
  if (!object(value) || !uuid(value.applicationId) || !statuses.includes(value.applicationStatus as AffairStatus)
    || !['REFUND_FULL', 'REFUND_PARTIAL'].includes(String(value.kind)) || !money(value.amountCents)
    || !(value.settlementId === null || idOk(value.settlementId))
    || !(value.settlementStatus === null || settlementStatuses.includes(String(value.settlementStatus))))
    return fail('退款');
  return { applicationId: value.applicationId, applicationStatus: value.applicationStatus as AffairStatus,
    kind: value.kind as AffairRefund['kind'], amountCents: amount(value.amountCents)!,
    settlementId: value.settlementId as string | null, settlementStatus: value.settlementStatus as string | null };
}
