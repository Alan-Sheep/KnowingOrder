package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.domain.dto.ChatAnswer;
import com.ragqa.rag.llm.PromptRegistry;
import com.ragqa.service.WebChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 30 题真实端到端评测显式开启；规则指标是辅助信号，不能替代人工事实与引用审核。 */
class WebChatEvaluationIT {

    @Test
    @Timeout(value = 50, unit = TimeUnit.MINUTES)
    @EnabledIfEnvironmentVariable(named = "RUN_WEB_CHAT_EVAL", matches = "true")
    void evaluateVersionedGoldenSetAndWriteMetadataOnlyReport() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<Map<String, Object>> results = new ArrayList<>();
        int sourceCases = 0;
        int retrieved = 0;
        int sourceHits = 0;
        int totalKeywords = 0;
        int matchedKeywords = 0;
        int refusalCases = 0;
        int correctRefusals = 0;
        try (var resource = getClass().getResourceAsStream("/eval/web-chat-v1.jsonl");
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        java.util.Objects.requireNonNull(resource), StandardCharsets.UTF_8));
                WebChatService service = WebChatLiveIT.service(true)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode sample = json.readTree(line);
                ChatAnswer answer = service.answer(sample.path("question").asText());
                String text = answer.answers().stream().map(ChatAnswer.Part::answer)
                        .collect(java.util.stream.Collectors.joining("\n")).toLowerCase(Locale.ROOT);
                List<String> urls = answer.answers().stream().flatMap(part -> part.sources().stream())
                        .map(ChatAnswer.Source::url).toList();
                boolean hit = false;
                if (!sample.path("expectSources").isEmpty()) {
                    sourceCases++;
                    if (!urls.isEmpty()) {
                        retrieved++;
                    }
                    for (JsonNode domain : sample.path("expectSources")) {
                        hit |= urls.stream().anyMatch(url -> domainMatches(url, domain.asText()));
                    }
                    if (hit) {
                        sourceHits++;
                    }
                }
                for (JsonNode keyword : sample.path("keywords")) {
                    totalKeywords++;
                    if (text.contains(keyword.asText().toLowerCase(Locale.ROOT))) {
                        matchedKeywords++;
                    }
                }
                boolean refused = answer.answers().stream().allMatch(part -> Set.of(
                        ChatAnswer.PartStatus.NO_EVIDENCE, ChatAnswer.PartStatus.INSUFFICIENT_EVIDENCE,
                        ChatAnswer.PartStatus.CLARIFICATION_NEEDED).contains(part.status()));
                if (sample.path("shouldRefuse").booleanValue()) {
                    refusalCases++;
                    if (refused) {
                        correctRefusals++;
                    }
                }
                results.add(Map.of("id", sample.path("id").asText(), "status", answer.status().name(),
                        "elapsedMillis", answer.elapsedMillis(), "expectedSourceHit", hit,
                        "refusedByContract", refused));
                System.out.println("评测完成：" + sample.path("id").asText() + "，状态=" + answer.status());
            }
        }
        Map<String, Object> metrics = Map.of("retrievalOnSourceCases", ratio(retrieved, sourceCases),
                "sourceHitRate", ratio(sourceHits, sourceCases), "keywordCoverage", ratio(matchedKeywords, totalKeywords),
                "refusalByContract", ratio(correctRefusals, refusalCases));
        Path target = Path.of("target", "eval", "web-chat-v1.json");
        writeReport(json, target, metrics, results);
        System.out.println("评测摘要：" + json.writeValueAsString(metrics));
        assertThat(results).hasSize(30);
        assertThat(ratio(correctRefusals, refusalCases)).as("拒答契约未达标，需复核回答，不能放宽断言")
                .isEqualTo(1.0);
        assertThat(ratio(sourceHits, sourceCases)).isGreaterThan(0.75);
        assertThat(ratio(matchedKeywords, totalKeywords)).isGreaterThan(0.70);
        assertThat(ratio(retrieved, sourceCases)).isGreaterThan(0.90);
    }

    /** 独立验证报告落盘，不需要运行付费评测或读取任何凭据。 */
    static void writeReport(ObjectMapper json, Path target, Map<String, Object> metrics,
            List<Map<String, Object>> results) throws java.io.IOException {
        // 注册表在启动时固定活跃版本；显式排列三个阶段，不依赖 Map 的遍历顺序。
        PromptRegistry registry = new PromptRegistry();
        List<String> promptIds = List.of("plan", "grade", "generate").stream()
                .map(name -> registry.get(name).id()).toList();
        Files.createDirectories(target.getParent());
        String report = json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "evaluatedAt", Instant.now().toString(), "promptIds", promptIds,
                "metrics", metrics, "cases", results,
                "limitations", "关键词及状态规则不等于事实准确性；一般建议中的文字拒答需人工复核，失败和超时不计为正确拒答。"));
        // Jackson 的默认排版使用平台换行，落盘时统一为项目约定的 LF。
        Files.writeString(target, report.replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
    }

    private static boolean domainMatches(String url, String domain) {
        String host = java.net.URI.create(url).getHost();
        return host != null && (host.equalsIgnoreCase(domain)
                || host.toLowerCase(Locale.ROOT).endsWith("." + domain.toLowerCase(Locale.ROOT)));
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }
}
