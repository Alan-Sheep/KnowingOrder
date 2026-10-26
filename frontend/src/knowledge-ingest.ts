import { EnterpriseError, positiveId } from './enterprise.ts';
import { workspaceUuid } from './knowledge-workspace.ts';

export interface IngestJob { id: string; documentId: string; versionId: string; expectedRevision: number; action: string;
  status: string; attempts: number; createdMs: number; deadlineMs: number; finishedMs: string | null; failureCode: string | null; message: string }
export function ingestJob(value: unknown): IngestJob {
  const v = value as IngestJob | null;
  if (!v || typeof v !== 'object' || !workspaceUuid(v.id) || !positiveId(v.documentId) || !positiveId(v.versionId)
    || !Number.isSafeInteger(v.expectedRevision) || v.expectedRevision < 1 || !['PARSE', 'REBUILD'].includes(v.action)
    || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(v.status)
    || !Number.isInteger(v.attempts) || v.attempts < 0 || v.attempts > 2
    || !Number.isSafeInteger(v.createdMs) || v.createdMs < 0 || !Number.isSafeInteger(v.deadlineMs) || v.deadlineMs !== v.createdMs + 30000
    || !(v.finishedMs === null || (typeof v.finishedMs === 'string' && /^\d{1,16}$/.test(v.finishedMs) && Number.isSafeInteger(Number(v.finishedMs))))
    || !(v.failureCode === null || ['INVALID_TEXT', 'ACCESS_REVOKED', 'REVISION_CONFLICT', 'PARSE_TIMEOUT',
      'ORIGINAL_UNAVAILABLE', 'INTERNAL_ERROR', 'TOTAL_TIMEOUT', 'RECOVERY_EXHAUSTED'].includes(v.failureCode))
    || typeof v.message !== 'string' || !v.message || v.message.length > 160) throw new EnterpriseError('入库任务数据没能正确读取，请稍后重试；若持续出现，请联系管理员。');
  if (['QUEUED', 'RUNNING'].includes(v.status) !== (v.finishedMs === null)) throw new EnterpriseError('入库任务终态不一致。');
  if ((v.finishedMs !== null && Number(v.finishedMs) < v.createdMs)
    || (['RUNNING', 'SUCCEEDED'].includes(v.status) && v.attempts < 1)
    || (v.status === 'QUEUED' && v.attempts !== 0)
    || (['QUEUED', 'RUNNING', 'SUCCEEDED', 'CANCELLED'].includes(v.status) && v.failureCode !== null)
    || (['FAILED', 'TIMED_OUT'].includes(v.status) && v.failureCode === null)) throw new EnterpriseError('入库任务状态字段不一致。');
  return v;
}
export function ingestJobs(v: unknown): IngestJob[] {
  if (!Array.isArray(v) || v.length > 10) throw new EnterpriseError('入库任务列表格式不正确。');
  return v.map(ingestJob);
}
export const ingestActive = (job: IngestJob) => ['QUEUED', 'RUNNING'].includes(job.status);
