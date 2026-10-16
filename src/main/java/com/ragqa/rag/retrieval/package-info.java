/**
 * 向量检索（预留）。
 *
 * <p>计划职责：</p>
 * <ul>
 *     <li>调用 embedding 模型把文本转成向量</li>
 *     <li>向量写入与删除（对接向量库，具体选型待定）</li>
 *     <li>相似度检索与 Top-K 召回、阈值过滤</li>
 *     <li>召回结果的重排（rerank）与去重</li>
 * </ul>
 *
 * <p>当前仅为包结构占位，不包含实现代码，也未引入任何向量库依赖。</p>
 */
package com.ragqa.rag.retrieval;
