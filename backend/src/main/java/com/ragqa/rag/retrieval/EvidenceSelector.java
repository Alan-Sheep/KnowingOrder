package com.ragqa.rag.retrieval;

import com.ragqa.domain.dto.SearchResult;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 单一搜索源的有界资料整理；不把供应商分数当作证据充分性的概率。 */
@Component
public class EvidenceSelector {

    /** 初版与现有检索量匹配：每题最多五条、每条正文 800 码点。 */
    public List<Entry> select(List<SearchResult> results) {
        List<SearchResult> ordered = new ArrayList<>(results);
        ordered.sort(Comparator.comparing(SearchResult::score, Comparator.nullsLast(Comparator.reverseOrder())));
        List<Entry> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SearchResult result : ordered) {
            String key = canonical(result.url());
            if (key == null || !seen.add(key)) {
                continue;
            }
            selected.add(new Entry(selected.size() + 1, truncate(result.title().strip(), 160),
                    result.url(), truncate(result.content().strip(), 800)));
            if (selected.size() == 5) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private static String canonical(String value) {
        if (value.length() > 2048) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("https") && !scheme.equals("http")) || uri.getHost() == null
                    || uri.getUserInfo() != null || value.codePoints().anyMatch(Character::isISOControl)) {
                return null;
            }
            // 只消除 fragment 和地址大小写差异，保留 query 与路径大小写，避免误合并不同资源。
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            return scheme + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT) + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static String truncate(String value, int codePoints) {
        int count = value.codePointCount(0, value.length());
        return count <= codePoints ? value : value.substring(0, value.offsetByCodePoints(0, codePoints));
    }

    public record Entry(int id, String title, String url, String content) {
        /** URL 不进入模型输入，引用由服务端映射，减少输入量和伪造来源的机会。 */
        public Map<String, Object> modelData() {
            return Map.of("id", id, "title", title, "content", content);
        }

        public SearchResult asSearchResult() {
            return new SearchResult(title, url, content, null);
        }
    }
}
