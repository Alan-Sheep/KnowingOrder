package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证评测资产的完整性；不把语料格式通过冒充真实问答质量通过。 */
class GoldenSetTest {
    @Test
    void goldenSetHasThirtyUniqueBoundedQuestionsAndAtLeastFifteenPercentRefusals() throws Exception {
        ObjectMapper json = new ObjectMapper();
        try (var stream = getClass().getResourceAsStream("/eval/web-chat-v1.jsonl");
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        java.util.Objects.requireNonNull(stream), StandardCharsets.UTF_8))) {
            List<JsonNode> cases = reader.lines().filter(line -> !line.isBlank()).map(line -> {
                try {
                    return json.readTree(line);
                } catch (Exception exception) {
                    throw new AssertionError("黄金集存在非法 JSON");
                }
            }).toList();
            assertThat(cases).hasSize(30);
            Set<String> ids = new HashSet<>();
            for (JsonNode sample : cases) {
                assertThat(ids.add(sample.path("id").asText())).isTrue();
                assertThat(sample.path("question").asText()).isNotBlank();
                assertThat(sample.path("question").asText().length()).isLessThanOrEqualTo(500);
                assertThat(sample.path("keywords").isArray()).isTrue();
                assertThat(sample.path("expectSources").isArray()).isTrue();
                assertThat(sample.path("shouldRefuse").isBoolean()).isTrue();
                assertThat(sample.path("category").asText()).isNotBlank();
            }
            assertThat(cases.stream().filter(sample -> sample.path("shouldRefuse").booleanValue()).count())
                    .isGreaterThanOrEqualTo(5);
            assertThat(cases.stream().map(sample -> sample.path("category").asText()).distinct().count())
                    .isGreaterThanOrEqualTo(8);
        }
    }
}
