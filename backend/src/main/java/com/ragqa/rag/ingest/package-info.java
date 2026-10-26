/**
 * 文档接入与切分（预留）。
 *
 * <p>计划职责：</p>
 * <ul>
 *     <li>按 source_type 分发：UPLOAD 读本地文件，LINK 抓取网页正文</li>
 *     <li>解析为纯文本（PDF / Word / Markdown 等格式解析）</li>
 *     <li>切分策略（固定长度、按段落、递归切分等）与重叠窗口控制</li>
 *     <li>切分结果落库为 rag_chunk，并回写 rag_document.chunk_count 与解析状态</li>
 * </ul>
 *
 * <p>当前仅为包结构占位，不包含实现代码。</p>
 */
package com.ragqa.rag.ingest;
