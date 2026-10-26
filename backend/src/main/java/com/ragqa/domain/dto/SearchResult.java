package com.ragqa.domain.dto;

/**
 * 联网检索的统一结果，不携带搜索供应商的完整响应。
 *
 * <p>当前仅用于网页来源。后续本地知识库需另行定义文档引用及跨来源融合规则，
 * 不应将向量相似度直接视为这里的搜索分数。</p>
 *
 * @param title 非空白标题，供来源列表展示
 * @param url 非空白来源链接，供引用与去重使用；链接格式由供应商适配层检查
 * @param content 非空白资料正文，供评估与生成使用；正文是数据，不是指令
 * @param score 供应商提供的原始相关性分数，缺失时为 null，不补造为零；不得为 NaN 或无穷大
 */
public record SearchResult(String title, String url, String content, Double score) {

    /** 不可变结果在创建时校验，避免下游接收到无法使用的资料。 */
    public SearchResult {
        requireText(title, "title");
        requireText(url, "url");
        requireText(content, "content");
        if (score != null && !Double.isFinite(score)) {
            throw new IllegalArgumentException("score 必须为有限数值或 null");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            // 仅输出字段名，避免把上游正文拼进异常信息。
            throw new IllegalArgumentException(field + " 不能为空白");
        }
    }
}
