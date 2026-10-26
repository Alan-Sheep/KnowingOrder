package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.rag.llm.PromptRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptRegistryTest {

    @Test
    void loadsAllVersionedResourcesAndDoesNotRecursivelyExpandUserContent() {
        PromptRegistry registry = new PromptRegistry();
        for (String name : new String[]{"plan", "grade", "generate"}) {
            PromptRegistry.Prompt prompt = registry.get(name);
            assertThat(prompt.id()).isEqualTo(name + (name.equals("plan") ? ":v3" : ":v2"));
            assertThat(prompt.system()).contains("角色", "输出格式");
            assertThat(prompt.render("{\"question\":\"{{payload}} 中文\"}"))
                    .contains("{\"question\":\"{{payload}} 中文\"}");
        }
        assertThatThrownBy(() -> registry.get("../../private")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingOrRepeatedPlaceholders() {
        assertThatThrownBy(() -> new PromptRegistry.Prompt("x:v1", "规则", "无占位符"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PromptRegistry.Prompt("x:v1", "规则", "{{payload}}{{payload}}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void evaluationReportTracksActivePromptVersions(@TempDir Path directory) throws Exception {
        ObjectMapper json = new ObjectMapper();
        Path target = directory.resolve("eval/report.json");
        WebChatEvaluationIT.writeReport(json, target, Map.of(), List.of());

        JsonNode report = json.readTree(Files.readString(target, StandardCharsets.UTF_8));
        PromptRegistry registry = new PromptRegistry();
        assertThat(report.path("promptIds")).extracting(JsonNode::asText).containsExactly(
                registry.get("plan").id(), registry.get("grade").id(), registry.get("generate").id());
    }

    @Test
    void evaluationReportPreservesMetadataWithoutDumpingPromptBodies(@TempDir Path directory) throws Exception {
        ObjectMapper json = new ObjectMapper();
        Path target = directory.resolve("eval/report.json");
        Map<String, Object> metrics = Map.of("sourceHitRate", 0.8);
        List<Map<String, Object>> cases = List.of(Map.of("id", "测试样例", "status", "PARTIAL"));
        WebChatEvaluationIT.writeReport(json, target, metrics, cases);

        String text = Files.readString(target, StandardCharsets.UTF_8);
        JsonNode report = json.readTree(text);
        assertThat(report.properties()).extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder("evaluatedAt", "promptIds", "metrics", "cases", "limitations");
        assertThat(Instant.parse(report.path("evaluatedAt").asText())).isNotNull();
        assertThat(report.path("metrics")).isEqualTo(json.valueToTree(metrics));
        assertThat(report.path("cases")).isEqualTo(json.valueToTree(cases));
        assertThat(report.path("limitations").asText()).contains("人工复核", "失败和超时不计为正确拒答");
        assertThat(text).contains("测试样例").doesNotContain("{{payload}}", "\"system\"", "\"userTemplate\"");
        assertThat(text.charAt(0)).isNotEqualTo('\uFEFF');
        assertThat(text).doesNotContain("\r");
    }
}
