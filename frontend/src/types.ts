/** 与后端 ChatAnswer 契约保持一致，区分传输成功和业务回答完成。 */
export type AnswerStatus = 'COMPLETED' | 'PARTIAL' | 'NO_ANSWER';
export type PartStatus = 'ANSWERED' | 'PARTIAL' | 'NO_EVIDENCE'
  | 'INSUFFICIENT_EVIDENCE' | 'CLARIFICATION_NEEDED' | 'DEPENDENCY_FAILED'
  | 'FAILED' | 'TIMED_OUT';

export interface Source {
  id: string;
  title: string;
  url: string;
  excerpt: string;
}

export interface AnswerPart {
  id: number;
  question: string;
  status: PartStatus;
  basis: 'GENERAL' | 'WEB';
  answer: string | null;
  message: string | null;
  sources: Source[];
}

export interface ChatAnswer {
  requestId: string;
  status: AnswerStatus;
  message: string;
  answers: AnswerPart[];
  elapsedMillis: number;
}

export interface ChatTurn {
  id: string;
  question: string;
  createdAt: number;
  state: 'pending' | 'done' | 'error';
  answer?: ChatAnswer;
  error?: string;
  demo?: boolean;
}

/** 这里只是标签页内的会话分组，不表示后端已支持多轮上下文。 */
export interface Conversation {
  id: string;
  title: string;
  createdAt: number;
  turns: ChatTurn[];
}
