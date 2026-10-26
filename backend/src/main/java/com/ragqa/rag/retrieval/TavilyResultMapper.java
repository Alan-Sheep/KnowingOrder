package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.domain.dto.SearchResult;
import com.ragqa.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/** 将 Tavily 搜索响应转换为统一结果，隔离供应商字段与下游业务。 */
@Component
public class TavilyResultMapper {

    /**
     * 保留供应商顺序，不在此处去重、排序或截断正文。
     *
     * @param response 已解析的搜索响应，不应包含在日志或异常消息中
     * @return 不可变结果列表；仅供应商明确返回空数组时正常返回空列表
     * @throws BusinessException 响应结构错误，或非空数组中的所有条目均不可用
     */
    public List<SearchResult> map(JsonNode response) {
        if (response == null || !response.isObject() || !response.path("results").isArray()) {
            throw invalidResponse();
        }
        JsonNode entries = response.get("results");
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode entry : entries) {
            if (!entry.isObject()) {
                continue;
            }
            String url = text(entry.get("url"));
            String content = text(entry.get("content"));
            if (url == null || content == null || !isWebUrl(url.strip())) {
                continue;
            }
            url = url.strip();
            String title = text(entry.get("title"));
            // 标题缺失不应丢弃有正文的来源，使用链接保证可展示、可追溯。
            results.add(new SearchResult(title == null ? url : title.strip(), url, content,
                    score(entry.get("score"))));
        }
        if (!entries.isEmpty() && results.isEmpty()) {
            // 全部无效与正常空结果分开，避免掩盖上游格式变化。
            throw invalidResponse();
        }
        return List.copyOf(results);
    }

    private static String text(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue() : null;
    }

    private static Double score(JsonNode value) {
        if (value == null || !value.isNumber()) {
            return null;
        }
        double score = value.doubleValue();
        return Double.isFinite(score) ? score : null;
    }

    private static boolean isWebUrl(String value) {
        try {
            URI uri = new URI(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static BusinessException invalidResponse() {
        // 不附带原始响应或解析异常，防止上游正文进入错误日志。
        return new BusinessException("搜索服务响应格式异常，请稍后重试");
    }
}
