/**
 * 问答编排（预留）。
 *
 * <p>计划职责：</p>
 * <ul>
 *     <li>串联完整链路：接收问题 → 检索 → 组装 Prompt → 调用模型 → 落库</li>
 *     <li>多轮会话上下文维护，按 conversation_id 拉取历史消息</li>
 *     <li>会话与消息的持久化编排（rag_conversation / rag_message）</li>
 *     <li>无召回结果时的兜底话术与拒答策略</li>
 * </ul>
 *
 * <p>当前仅为包结构占位，不包含实现代码。</p>
 */
package com.ragqa.rag.chat;
