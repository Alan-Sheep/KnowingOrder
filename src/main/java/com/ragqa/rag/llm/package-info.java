/**
 * 大模型调用（预留）。
 *
 * <p>计划职责：</p>
 * <ul>
 *     <li>Prompt 模板组装：把检索召回的分块与用户问题拼成上下文</li>
 *     <li>模型 API 调用封装，含超时、重试与降级策略</li>
 *     <li>流式输出（SSE）支持</li>
 *     <li>Token 计量与成本统计，结果回写 rag_message.token_count</li>
 * </ul>
 *
 * <p>当前仅为包结构占位，不包含实现代码，也未引入任何大模型 SDK。</p>
 */
package com.ragqa.rag.llm;
