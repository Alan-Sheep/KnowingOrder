package com.ragqa.domain.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证结果契约的反序列化与输入边界，不调用搜索 API。 */
class SearchResultTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jsonKeepsFourFieldsAndDoesNotInventMissingScore() throws Exception {
        // 人工构造的契约样例，不是实际搜索响应。
        String json = """
                {"title":"检索测试资料","url":"https://example.com/article","content":"测试正文"}
                """;
        SearchResult result = mapper.readValue(json, SearchResult.class);
        assertThat(result.score()).isNull();
        assertThat(mapper.readTree(mapper.writeValueAsString(result)).size()).isEqualTo(4);
        assertThat(mapper.readTree(mapper.writeValueAsString(result)).get("score").isNull()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidText")
    void rejectsMissingTextWithoutEchoingContent(String title, String url, String content, String field) {
        assertThatThrownBy(() -> new SearchResult(title, url, content, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(field + " 不能为空白");
    }

    static Stream<Arguments> invalidText() {
        return Stream.of(
                Arguments.of(null, "https://example.com", "正文", "title"),
                Arguments.of(" ", "https://example.com", "正文", "title"),
                Arguments.of("标题", null, "正文", "url"),
                Arguments.of("标题", "\t", "正文", "url"),
                Arguments.of("标题", "https://example.com", null, "content"),
                Arguments.of("标题", "https://example.com", "\n", "content"));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsNonFiniteScores(double score) {
        assertThatThrownBy(() -> new SearchResult("标题", "https://example.com", "正文", score))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("score 必须为有限数值或 null");
    }

    @Test
    void jsonDeserializationAlsoEnforcesTextContract() {
        assertThatThrownBy(() -> mapper.readValue("""
                {"title":"标题","url":"https://example.com","content":" "}
                """, SearchResult.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
