import type { Conversation } from './types';
import { uuid } from './uuid';

/** 只由用户点击“体验示例”加载，不调用模型，不冒充真实检索结果。 */
export function createDemo(): Conversation {
  const now = Date.now();
  return {
    id: uuid(),
    title: '认识 RAG：从问题到有依据的回答',
    createdAt: now,
    turns: [{
      id: uuid(),
      question: 'RAG 是什么？它和直接问大模型有什么区别？资料不够的时候会怎么办？',
      createdAt: now,
      state: 'done',
      demo: true,
      answer: {
        requestId: 'ui-demo-not-a-real-request',
        status: 'PARTIAL',
        message: '界面演示：展示多问题回答、引用来源和资料不足三种界面元素。',
        elapsedMillis: 0,
        answers: [{
          id: 1,
          question: 'RAG 是什么？',
          status: 'ANSWERED',
          basis: 'WEB',
          answer: 'RAG，即检索增强生成。可以把它理解为给大模型配一本能随时翻阅的参考书。\n\n先从外部资料中检索与问题相关的内容，再让模型根据这些内容组织回答，让答案有可核对的依据。[q1-s1]',
          message: null,
          sources: [{
            id: 'q1-s1',
            title: 'Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks',
            url: 'https://arxiv.org/abs/2005.11401',
            excerpt: '演示用来源：RAG 研究论文。此处内容为预置的界面示例，不代表本次执行了联网检索。'
          }]
        }, {
          id: 2,
          question: '和直接问大模型有什么区别？',
          status: 'ANSWERED',
          basis: 'GENERAL',
          answer: '直接问模型，主要依赖它训练时学到的知识。RAG 会额外提供检索到的资料，适用于需要最新信息、领域资料或可核对出处的场景。\n\n检索本身也可能找错或遗漏，仍然需要判断资料是否足以支撑回答。',
          message: '一般性说明，不代表本次进行了联网核验。',
          sources: []
        }, {
          id: 3,
          question: '资料不足时，怎样确保答案可信？',
          status: 'INSUFFICIENT_EVIDENCE',
          basis: 'WEB',
          answer: null,
          message: '现有资料不足以支持完整结论。可信的回答应明确说明缺口，保留能确认的信息，避免把推测当作事实。',
          sources: []
        }]
      }
    }]
  };
}
