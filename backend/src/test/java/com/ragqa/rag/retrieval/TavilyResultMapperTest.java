package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ragqa.domain.dto.SearchResult;
import com.ragqa.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用人工构造的响应验证适配策略，不依赖真实 API 或密钥。 */
class TavilyResultMapperTest {

    private final ObjectMapper json = new ObjectMapper();
    private final TavilyResultMapper mapper = new TavilyResultMapper();

    @Test
    void mapsFieldsInOriginalOrderWithoutSortingDeduplicatingOrTruncating() throws Exception {
        JsonNode response = json.readTree("""
                {"results":[
                  {"title":" 资料一 ","url":" https://example.com/a?q=1#part ",
                   "content":" 第一段资料。 ","score":0.6,"raw_content":"不使用此字段"},
                  {"title":"资料二","url":"https://example.com/a?q=1#part",
                   "content":"第二段资料。","score":0.9}
                ],"answer":"不使用供应商生成的答案"}
                """);

        List<SearchResult> results = mapper.map(response);

        assertThat(results).containsExactly(
                new SearchResult("资料一", "https://example.com/a?q=1#part", " 第一段资料。 ", 0.6),
                new SearchResult("资料二", "https://example.com/a?q=1#part", "第二段资料。", 0.9));
        assertThatThrownBy(() -> results.add(results.get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "\" \"", "42", "{}"})
    void usesUrlAsTitleWhenTitleIsMissingOrUnusable(String title) throws Exception {
        ObjectNode entry = validEntry();
        if (title != null) {
            entry.set("title", json.readTree(title));
        }
        SearchResult result = mapper.map(response(entry)).get(0);
        assertThat(result.title()).isEqualTo("https://example.com/article");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "\"0.8\"", "false", "{}", "1e999"})
    void doesNotInventOrCoerceMissingAndInvalidScores(String score) throws Exception {
        ObjectNode entry = validEntry();
        if (score != null) {
            entry.set("score", json.readTree(score));
        }
        assertThat(mapper.map(response(entry)).get(0).score()).isNull();
    }

    @Test
    void keepsFiniteProviderScoreWithoutInventingRangeLimits() {
        ObjectNode entry = validEntry();
        entry.put("score", -2.5);
        assertThat(mapper.map(response(entry)).get(0).score()).isEqualTo(-2.5);
    }

    @Test
    void keepsValidEntryWhenOtherEntriesHaveWrongTypesOrMissingRequiredFields() throws Exception {
        ObjectNode response = json.createObjectNode();
        response.putArray("results").addNull().add(123)
                .add(json.readTree("{\"url\":42,\"content\":\"正文\"}"))
                .add(json.readTree("{\"url\":\"https://example.com\",\"content\":false}"))
                .add(json.readTree("{\"url\":\"https://example.com\",\"content\":\" \"}"))
                .add(validEntry());
        assertThat(mapper.map(response)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "file:///tmp/article", "/article",
            "https:///article", "https://user:password@example.com", "https://exa mple.com", " "})
    void rejectsUnusableSourceUrlsWithoutEchoingThem(String url) {
        ObjectNode entry = validEntry();
        entry.put("url", url);
        assertThatThrownBy(() -> mapper.map(response(entry)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("搜索服务响应格式异常，请稍后重试");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/article", "HTTPS://example.com/article"})
    void acceptsHttpAndHttpsSources(String url) {
        ObjectNode entry = validEntry();
        entry.put("url", url);
        assertThat(mapper.map(response(entry)).get(0).url()).isEqualTo(url);
    }

    @Test
    void acceptsExplicitlyEmptyResults() throws Exception {
        assertThat(mapper.map(json.readTree("{\"results\":[]}"))).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "[]", "{}", "{\"results\":null}",
            "{\"results\":{}}", "{\"results\":[null,{}]}"})
    void distinguishesInvalidResponsesFromNoMatches(String response) throws Exception {
        JsonNode body = response == null ? null : json.readTree(response);
        assertThatThrownBy(() -> mapper.map(body))
                .isInstanceOf(BusinessException.class)
                .hasMessage("搜索服务响应格式异常，请稍后重试");
    }

    private ObjectNode validEntry() {
        return json.createObjectNode().put("url", "https://example.com/article").put("content", "资料正文");
    }

    private ObjectNode response(JsonNode entry) {
        ObjectNode response = json.createObjectNode();
        response.putArray("results").add(entry);
        return response;
    }
}
