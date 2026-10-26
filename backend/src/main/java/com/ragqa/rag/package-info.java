/**
 * RAG 引擎组件（与分层包 controller/service 等正交）。
 *
 * <ul>
 *     <li>{@code ingest}   文档解析与分块：读取原始文件、抽取纯文本、按策略切分并生成 rag_chunk</li>
 *     <li>{@code retrieval} 向量检索：embedding 生成、向量库写入与相似度查询、召回结果重排</li>
 *     <li>{@code llm}      大模型调用：Prompt 组装、模型 API 调用、流式输出、Token 计量</li>
 *     <li>{@code chat}     问答编排：串联检索与生成，维护多轮会话上下文</li>
 * </ul>
 *
 * <p>当前仅为包结构占位，不包含实现代码。</p>
 */
package com.ragqa.rag;
