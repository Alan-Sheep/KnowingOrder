/**
 * 检索来源适配与结果处理。
 *
 * <p>首版联网检索职责：</p>
 * <ul>
 *     <li>适配 Tavily 等外部来源，输出统一的 {@link com.ragqa.domain.dto.SearchResult}</li>
 *     <li>处理外部调用的超时、有限重试与失败状态</li>
 *     <li>对召回资料去重、排序，并按后续规则限制正文长度</li>
 * </ul>
 *
 * <p>当前正在定义联网结果契约，供应商调用尚未实现。
 * 本地知识库检索及混合检索将在后续单元接入，当前不引入向量库依赖。</p>
 */
package com.ragqa.rag.retrieval;
