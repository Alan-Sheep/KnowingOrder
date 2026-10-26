import { EnterpriseError, positiveId } from './enterprise.ts';

export interface KnowledgeDocument { id: string; knowledgeSpaceId: string; title: string; ownerMemberId: string; ownerMemberName: string | null; publishedVersionId: string | null; revision: number }
export interface DocumentVersion { id: string; documentId: string; versionNo: number; originalName: string; fileSize: number; parseStatus: string; publicationStatus: string; chunkCount: number; failureCode: string | null; validFromMs: string | null; validUntilMs: string | null }
export interface KnowledgeChunk { id: string; chunkIndex: number; content: string; startOffset: number; endOffset: number; startLine: number; endLine: number }
export interface KnowledgeGrant { id: string; memberId: string | null; departmentId: string | null; permission: string; status: number }
export interface PageData<T> { records: T[]; total: number; pageNum: number; pageSize: number; pages: number }
export interface UploadMetadata { requestId: string; documentId: string | null; expectedRevision: number | null; title: string; validFromMs: string | null; validUntilMs: string | null }

const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const nonnegative = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 0;
const positive = (v: unknown): v is number => nonnegative(v) && v > 0;
const nullableId = (v: unknown) => v === null || positiveId(v);
function invalid(): never { throw new EnterpriseError('知识管理数据没能正确读取，请稍后重试；若持续出现，请联系管理员。'); }

export function documentView(v: unknown): KnowledgeDocument {
  if (!object(v) || !positiveId(v.id) || !positiveId(v.knowledgeSpaceId) || typeof v.title !== 'string'
    || !v.title || v.title.length > 255 || !positiveId(v.ownerMemberId) || !nullableId(v.publishedVersionId) || !positive(v.revision)
    || !(v.ownerMemberName === undefined || v.ownerMemberName === null || typeof v.ownerMemberName === 'string')) return invalid();
  // 兼容尚未返回姓名的旧后端：缺失时置空，由界面决定如何显示。
  return { ...(v as unknown as KnowledgeDocument), ownerMemberName: typeof v.ownerMemberName === 'string' ? v.ownerMemberName : null };
}

export function versionView(v: unknown): DocumentVersion {
  if (!object(v) || !positiveId(v.id) || !positiveId(v.documentId) || !positive(v.versionNo)
    || typeof v.originalName !== 'string' || !v.originalName || !positive(v.fileSize) || v.fileSize > 1048576
    || !['PENDING', 'READY', 'FAILED'].includes(String(v.parseStatus))
    || !['DRAFT', 'PUBLISHED', 'WITHDRAWN', 'DELETED'].includes(String(v.publicationStatus)) || !nonnegative(v.chunkCount)
    || !(v.failureCode === null || ['INVALID_TEXT', 'PARSE_TIMEOUT', 'ORIGINAL_UNAVAILABLE'].includes(String(v.failureCode)))
    || ![v.validFromMs, v.validUntilMs].every(value => value === null || (typeof value === 'string' && /^\d{1,19}$/.test(value) && BigInt(value) <= 9223372036854775807n))) return invalid();
  return v as unknown as DocumentVersion;
}

export function grantView(v: unknown): KnowledgeGrant {
  if (!object(v) || !positiveId(v.id) || !nullableId(v.memberId) || !nullableId(v.departmentId)
    || (v.memberId === null) === (v.departmentId === null)
    || !['READ', 'MAINTAIN'].includes(String(v.permission)) || ![0, 1].includes(Number(v.status)) || typeof v.status !== 'number') return invalid();
  return v as unknown as KnowledgeGrant;
}

export function chunkView(v: unknown): KnowledgeChunk {
  if (!object(v) || !positiveId(v.id) || !nonnegative(v.chunkIndex) || typeof v.content !== 'string' || v.content.length > 1000
    || !nonnegative(v.startOffset) || !positive(v.endOffset) || v.startOffset >= v.endOffset
    || v.endOffset - v.startOffset !== v.content.length || !positive(v.startLine) || !positive(v.endLine) || v.endLine < v.startLine) return invalid();
  return v as unknown as KnowledgeChunk;
}

export function pageView<T>(v: unknown, read: (value: unknown) => T): PageData<T> {
  if (!object(v) || !Array.isArray(v.records) || !nonnegative(v.total) || !positive(v.pageNum)
    || !positive(v.pageSize) || v.pageSize > 500 || !nonnegative(v.pages) || v.records.length > v.pageSize) return invalid();
  return { records: v.records.map(read), total: v.total, pageNum: v.pageNum, pageSize: v.pageSize, pages: v.pages };
}

export function grantsView(v: unknown): KnowledgeGrant[] {
  if (!Array.isArray(v) || v.length > 500) return invalid();
  return v.map(grantView);
}
