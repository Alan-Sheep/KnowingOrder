import { answerView } from './knowledge-answer.ts';
import type { KnowledgeAnswer } from './knowledge-answer';

export interface WorkspaceChat { id: string; title: string; createdMs: number }
export interface WorkspaceJob {
  id: string; chatId: string; question: string; status: string; createdMs: number; deadlineMs: number;
  finishedMs: number | null; answer: KnowledgeAnswer | null; message: string;
}
export interface KnowledgeFeedback {
  id: string; jobId: string; chunkId: string | null; category: string; commentText: string; status: string;
  resolution: string | null; resolvedBy: string | null; revision: number; createdMs: number; question: string;
}
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);
export const workspaceUuid = (v: unknown): v is string => typeof v === 'string'
  && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v);
const text = (v: unknown, max: number): v is string => typeof v === 'string' && !!v.trim() && v.length <= max;
const time = (v: unknown): v is number => Number.isSafeInteger(v) && Number(v) >= 0;
const nullableTime = (v: unknown): boolean => v == null || time(v)
  || (typeof v === 'string' && /^(0|[1-9]\d*)$/.test(v) && time(Number(v)));
const identifier = (v: unknown): v is string => typeof v === 'string' && /^[1-9]\d{0,18}$/.test(v)
  && BigInt(v) <= 9223372036854775807n;
const bad = (): never => { throw new Error('会话、任务或反馈数据没能正确读取，请稍后重试；若持续出现，请联系管理员。'); };
export function workspaceChats(value: unknown): WorkspaceChat[] {
  if (!Array.isArray(value) || value.length > 50) return bad();
  const result = value.map(v => {
    if (!object(v) || !workspaceUuid(v.id) || !text(v.title, 80) || !time(v.createdMs)) return bad();
    return { id: v.id, title: v.title, createdMs: v.createdMs };
  });
  if (new Set(result.map(v => v.id)).size !== result.length) return bad();
  return result;
}
export function workspaceJob(v: unknown): WorkspaceJob {
  if (!object(v) || !workspaceUuid(v.id) || !workspaceUuid(v.chatId) || !text(v.question, 1200)
    || !text(v.message, 1000) || !time(v.createdMs) || !time(v.deadlineMs) || v.deadlineMs <= v.createdMs
    || v.deadlineMs - v.createdMs > 30000 || !nullableTime(v.finishedMs)
    || !['PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(String(v.status))) return bad();
  const terminal = !['PENDING', 'RUNNING'].includes(String(v.status));
  if (terminal !== (v.finishedMs != null) || (v.finishedMs != null && Number(v.finishedMs) < v.createdMs)) return bad();
  if (v.answer != null && v.status !== 'COMPLETED') return bad();
  return { id: v.id, chatId: v.chatId, question: v.question, status: String(v.status), createdMs: v.createdMs,
    deadlineMs: v.deadlineMs, finishedMs: v.finishedMs == null ? null : Number(v.finishedMs),
    answer: v.answer == null ? null : answerView(v.answer), message: v.message };
}
export function workspaceHistory(v: unknown): WorkspaceJob[] {
  if (!Array.isArray(v) || v.length > 50) return bad();
  const jobs = v.map(workspaceJob);
  if (new Set(jobs.map(j => j.id)).size !== jobs.length) return bad();
  return jobs;
}
export function feedbackList(v: unknown): KnowledgeFeedback[] {
  if (!Array.isArray(v) || v.length > 100) return bad();
  return v.map(item => {
    if (!object(item) || !workspaceUuid(item.id) || !workspaceUuid(item.jobId)
      || !['INCORRECT', 'MISSING', 'OUTDATED', 'OTHER'].includes(String(item.category))
      || !['OPEN', 'RESOLVED', 'DISMISSED'].includes(String(item.status)) || !text(item.commentText, 1000) || !text(item.question, 1200)
      || !time(item.createdMs) || !Number.isSafeInteger(item.revision) || Number(item.revision) < 1
      || (item.chunkId != null && !identifier(item.chunkId)) || (item.resolvedBy != null && !identifier(item.resolvedBy))
      || (item.resolution != null && !text(item.resolution, 1000))) return bad();
    if (item.status === 'OPEN' ? item.resolution != null || item.resolvedBy != null
      : item.resolution == null || item.resolvedBy == null) return bad();
    return { id: item.id, jobId: item.jobId, chunkId: item.chunkId == null ? null : String(item.chunkId),
      category: String(item.category), commentText: item.commentText, status: String(item.status),
      resolution: item.resolution == null ? null : String(item.resolution),
      resolvedBy: item.resolvedBy == null ? null : String(item.resolvedBy), revision: Number(item.revision), createdMs: item.createdMs, question: item.question };
  });
}
