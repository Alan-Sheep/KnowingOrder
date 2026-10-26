import type { ChatAnswer, AnswerPart, Source } from './types';
import type { Language, TimeZone } from './preferences';

const partStatuses = new Set(['ANSWERED', 'PARTIAL', 'NO_EVIDENCE',
  'INSUFFICIENT_EVIDENCE', 'CLARIFICATION_NEEDED', 'DEPENDENCY_FAILED', 'FAILED', 'TIMED_OUT']);

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function source(value: unknown): value is Source {
  return record(value) && ['id', 'title', 'url', 'excerpt'].every(key => typeof value[key] === 'string');
}

function part(value: unknown): value is AnswerPart {
  return record(value) && Number.isSafeInteger(value.id) && Number(value.id) > 0
    && typeof value.question === 'string' && partStatuses.has(String(value.status))
    && (value.basis === 'WEB' || value.basis === 'GENERAL')
    && (value.answer === null || typeof value.answer === 'string')
    && (value.message === null || typeof value.message === 'string')
    && Array.isArray(value.sources) && value.sources.every(source);
}

export function validateAnswer(value: unknown): value is ChatAnswer {
  return record(value) && typeof value.requestId === 'string'
    && ['COMPLETED', 'PARTIAL', 'NO_ANSWER'].includes(String(value.status))
    && typeof value.message === 'string' && typeof value.elapsedMillis === 'number'
    && Number.isFinite(value.elapsedMillis) && value.elapsedMillis >= 0
    && Array.isArray(value.answers) && value.answers.length > 0 && value.answers.length <= 3
    && value.answers.every(part)
    && new Set(value.answers.map(item => item.id)).size === value.answers.length;
}

/** 仅开放网页链接，不允许模型输出执行脚本、文件协议或带凭据的链接。 */
export function safeUrl(value: string): string | undefined {
  try {
    const url = new URL(value);
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password
      ? url.href : undefined;
  } catch {
    return undefined;
  }
}

/** 浏览器等待上限略大于后端 90 秒总预算；未知结果不自动重发，避免重复计费。 */
export async function sendQuestion(question: string, signal?: AbortSignal, timeoutMs = 95000,
  timeZone: TimeZone = 'Asia/Shanghai', answerLanguage: Language = 'zh-CN'): Promise<ChatAnswer> {
  // 每次提交固定一份时间快照；设备时间不可信，后端仍须使用自身接收时间判断“今天”。
  const requestPayload = { question, clientSubmittedAt: new Date().toISOString(), timeZone, answerLanguage };
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort();
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) abort();
  const timer = window.setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
  try {
    const response = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestPayload),
      signal: controller.signal,
      cache: 'no-store',
      credentials: 'omit'
    });
    if (!response.ok) {
      if (response.status === 429) throw new Error('当前请求较多，请稍后再试。你的问题仍保留在对话中。');
      if ([401, 403].includes(response.status)) throw new Error('当前访问未获授权，请检查后端的本地访问限制或登录配置。');
      if ([500, 502, 503, 504].includes(response.status)) throw new Error('问答服务暂时不可用，请确认后端已启动，稍后手动重试。');
      if (response.status === 404) throw new Error('未找到问答接口，请确认后端已启用 dev 环境并监听 8080 端口。');
      throw new Error(`请求未能完成（HTTP ${response.status}），请稍后手动重试。`);
    }
    const payload: unknown = await response.json().catch(() => null);
    if (!record(payload) || typeof payload.code !== 'number') throw new Error('服务返回了无法识别的数据，请检查接口版本。');
    if (payload.code !== 0) throw new Error('这次请求未能完成，请检查服务配置或稍后手动重试。');
    if (!validateAnswer(payload.data)) throw new Error('回答数据不完整，请检查接口契约；本次不会自动重复提交。');
    return payload.data;
  } catch (error) {
    if (timedOut) throw new Error('等待已超过 95 秒，已停止本次连接。后台可能仍在收尾，请勿连续重复提交。');
    if (signal?.aborted) throw new Error('已停止等待此回答。仅断开前端连接，不能保证后台任务立即取消。');
    if (error instanceof TypeError) throw new Error('无法连接问答服务，请检查网络及本机 8080 端口的后端服务。');
    throw error;
  } finally {
    window.clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}
