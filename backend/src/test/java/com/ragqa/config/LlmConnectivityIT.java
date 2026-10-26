package com.ragqa.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.rag.chat.TaskDeadline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * 手动开启的真实 Responses 连通性测试，不启动 Spring 或连接数据库。
 * 连通性用 RUN_LLM_LIVE_TEST 开启；多问题评测用独立的 RUN_LLM_SCENARIO_TESTS 开启。
 * 普通 Maven 构建不选取 IT，额外开关防止 IDE 批量运行时意外产生付费调用。
 */
class LlmConnectivityIT {

    @Test
    @Timeout(65)
    @EnabledIfEnvironmentVariable(named = "RUN_LLM_LIVE_TEST", matches = "true")
    void configuredRelayCanReturnCompletedText() {
        RagApiProperties properties = configuredProperties();
        RagApiProperties.Llm settings = properties.getLlm();
        RestClient client = new RagHttpClientConfig().llmRestClient(RestClient.builder(), properties);

        int characters = probe(client, settings.getApiKey(), settings.getModel());

        // 不打印密钥、模型输出、供应商响应编号、请求或异常原文。
        System.out.println("模型连通性验证通过：Responses 请求完成，收到非空文本，字符数=" + characters);
    }

    @Test
    @Timeout(95)
    @EnabledIfEnvironmentVariable(named = "RUN_LLM_SCENARIO_TESTS", matches = "true")
    void configuredRelayCanAnalyzeMultipleQuestions() {
        TaskDeadline deadline = TaskDeadline.start(Duration.ofSeconds(90));
        RagApiProperties properties = configuredProperties();
        RagApiProperties.Llm settings = properties.getLlm();
        RestClient client = new RagHttpClientConfig().llmRestClient(RestClient.builder(), properties);
        String instructions = analysisInstructions();
        List<Scenario> scenarios = scenarios();
        for (Scenario scenario : scenarios) {
            String text = analyze(client, settings.getApiKey(), settings.getModel(), instructions,
                    scenario.input(), deadline);
            verifyAnalysis(text, scenario);
            // 只打印固定用例标识和已验证的结构摘要，不打印模型正文或配置值。
            System.out.println("多问题意图评测通过：场景=" + scenario.name() + "，问题数="
                    + scenario.anchors().size() + "，展示顺序=" + scenario.displayOrder());
        }
        System.out.println("多问题意图评测完成：" + scenarios.size() + "/" + scenarios.size()
                + "；仅验证分析契约，未执行联网检索或后台调度");
    }

    private static RagApiProperties configuredProperties() {
        RagApiProperties properties = new RagApiProperties();
        RagApiProperties.Llm settings = properties.getLlm();
        settings.setBaseUrl(requiredEnvironment("DASHSCOPE_BASE_URL"));
        settings.setApiKey(requiredEnvironment("DASHSCOPE_API_KEY"));
        settings.setModel(requiredEnvironment("LLM_MODEL"));
        validateBaseUrl(settings.getBaseUrl());
        return properties;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw failure("缺少环境变量 " + name + "；请配置在本次 JUnit 运行配置中");
        }
        return value;
    }

    static void validateBaseUrl(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPath().endsWith("/responses") || uri.getPath().endsWith("/chat/completions")) {
                throw failure("模型基础地址必须为不含凭据、查询参数及具体调用路径的 HTTPS 地址");
            }
        } catch (IllegalArgumentException exception) {
            throw failure("模型基础地址格式错误；请检查基础地址，不要在地址中填写密钥");
        }
    }

    /** 探测接口契约，不构成业务模型客户端；返回长度摘要，绝不向外抛出原始响应。 */
    static int probe(RestClient client, String apiKey, String model) {
        String text = requestCompletedText(client, apiKey, model, "请只回复 OK。", 256, null);
        return text.codePointCount(0, text.length());
    }

    /** 固定评测输入与系统规则分开传入；共用总预算且每个场景只发送一次。 */
    static String analyze(RestClient client, String apiKey, String model, String instructions,
            String question, TaskDeadline deadline) {
        return requestCompletedText(client, apiKey, model, List.of(
                Map.of("role", "system", "content", instructions),
                Map.of("role", "user", "content", question)), 2048, deadline);
    }

    private static String requestCompletedText(RestClient client, String apiKey, String model,
            Object input, int maxOutputTokens, TaskDeadline deadline) {
        if (apiKey == null || apiKey.isBlank()
                || apiKey.chars().anyMatch(value -> value < 33 || value > 126)) {
            throw failure("模型密钥缺失或包含不适合请求头的字符；请在本机检查");
        }
        if (model == null || model.isBlank()) {
            throw failure("模型 ID 不能为空；请使用中转平台提供的精确标识");
        }
        try {
            String text = client.post().uri("/responses").contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .httpRequest(request -> {
                        if (deadline != null) {
                            BoundedJdkClientHttpRequestFactory.useRemainingBudget(request, deadline::remaining);
                        }
                    })
                    .body(Map.of("model", model, "input", input, "max_output_tokens", maxOutputTokens,
                            "stream", false, "store", false))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status != 200) {
                            // 上游错误可能回显密钥；不读取错误正文，不建立原始异常链。
                            throw failure(safeStatusMessage(status));
                        }
                        try {
                            MediaType type = response.getHeaders().getContentType();
                            if (type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
                                throw failure("响应格式异常：期望 JSON，未确认模型连通成功");
                            }
                            JsonNode body = new ObjectMapper().readerFor(JsonNode.class)
                                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                    .readValue(response.getBody());
                            return completedText(body);
                        } catch (IOException | IllegalArgumentException exception) {
                            throw failure("响应格式异常：无法读取完整的 Responses JSON");
                        }
                    });
            if (text == null) {
                throw failure("响应为空，未确认模型连通成功");
            }
            return text;
        } catch (ResourceAccessException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException
                        || cause instanceof java.net.SocketTimeoutException) {
                    throw failure("模型请求超时，已结束本次验证；未自动重试");
                }
            }
            throw failure("模型网络连接失败或请求被中断；请检查网络与服务地址，未自动重试");
        } catch (RestClientException | IllegalArgumentException exception) {
            throw failure("模型请求或响应处理失败；未确认连通成功，未自动重试");
        }
    }

    private static String completedText(JsonNode body) {
        if (body == null || !body.isObject() || !"response".equals(body.path("object").asText())
                || !body.path("error").isNull() && body.has("error")) {
            throw failure("响应格式异常：未收到有效的 Responses 对象");
        }
        if ("incomplete".equals(body.path("status").asText())) {
            throw failure("请求已返回但生成未完成；可能达到输出预算，不能判定完整生成成功");
        }
        if (!"completed".equals(body.path("status").asText()) || !body.path("output").isArray()) {
            throw failure("响应未完成或缺少 output，未确认完整生成成功");
        }
        StringBuilder completed = new StringBuilder();
        for (JsonNode item : body.path("output")) {
            if ("message".equals(item.path("type").asText())
                    && "assistant".equals(item.path("role").asText()) && item.path("content").isArray()) {
                for (JsonNode content : item.path("content")) {
                    if ("output_text".equals(content.path("type").asText()) && content.path("text").isTextual()) {
                        // 片段之间的空格可能有语义，合并后再判断整体是否为空白。
                        completed.append(content.path("text").textValue());
                    }
                }
            }
        }
        if (completed.toString().isBlank()) {
            throw failure("响应完成但没有可用文本；未确认文本生成成功");
        }
        return completed.toString();
    }

    static String analysisInstructions() {
        try (InputStream input = LlmConnectivityIT.class.getResourceAsStream(
                "/prompts/multi-question/v1_system.txt")) {
            if (input == null) {
                throw failure("多问题评测模板缺失：请确认测试资源目录已加载");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw failure("多问题评测模板读取失败；未发送模型请求");
        }
    }

    /** 固定样例的人工预期，用于小范围回归，不作为通用语义评分器或生产路由器。 */
    static List<Scenario> scenarios() {
        return List.of(
                new Scenario("无标点混合三问", "晚上放了学回家吃什么要去面试的话需要准备啥agent和java两个哪个就业好？",
                        List.of(List.of("吃什么"), List.of("面试"), List.of("agent", "java", "就业")),
                        List.of(List.of(), List.of(), List.of()), List.of(false, false, true), List.of(1, 2, 3)),
                new Scenario("单问包含多个条件", "推荐一顿晚饭，预算20元，十分钟做好，不吃辣。",
                        List.of(List.of("晚饭", "20元", "十分钟", "不吃辣")),
                        List.of(List.of()), List.of(false), List.of(1)),
                new Scenario("指定展示顺序", "推荐晚饭；告诉我Java面试准备；比较Agent和Java就业方向。先回答就业方向，再说面试准备，最后说晚饭。",
                        List.of(List.of("晚饭"), List.of("面试"), List.of("agent", "java", "就业")),
                        List.of(List.of(), List.of(), List.of()), List.of(false, false, true), List.of(3, 2, 1)),
                new Scenario("必要前置依赖", "先查北京Java和Agent岗位的最新要求，再根据这些要求列出我的面试准备清单，最后给我一道晚饭建议。",
                        List.of(List.of("北京", "java", "agent", "要求"), List.of("面试"), List.of("晚饭")),
                        List.of(List.of(), List.of(1), List.of()), List.of(true, false, false), List.of(1, 2, 3)));
    }

    static void verifyAnalysis(String text, Scenario scenario) {
        JsonNode plan;
        try {
            plan = new ObjectMapper().readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readValue(text);
        } catch (IOException | IllegalArgumentException exception) {
            throw failure("多问题评测未通过：场景=" + scenario.name() + "，检查项=JSON格式；未打印模型正文");
        }
        requirePlan(hasFields(plan, "questions", "display_order", "needs_clarification"), scenario, "顶层字段");
        requirePlan(plan.path("needs_clarification").isBoolean()
                && !plan.path("needs_clarification").booleanValue(), scenario, "不必要的澄清");
        JsonNode questions = plan.path("questions");
        requirePlan(questions.isArray() && questions.size() == scenario.anchors().size(), scenario, "问题数量");
        int previousEnd = 0;
        for (int index = 0; index < questions.size(); index++) {
            JsonNode question = questions.get(index);
            requirePlan(hasFields(question, "id", "source_text", "depends_on", "needs_search"), scenario, "子问题字段");
            requirePlan(question.path("id").isInt() && question.path("id").intValue() == index + 1,
                    scenario, "问题编号");
            requirePlan(question.path("source_text").isTextual(), scenario, "原文片段类型");
            String fragment = question.path("source_text").textValue();
            int start = scenario.input().indexOf(fragment);
            requirePlan(!fragment.isBlank() && start >= previousEnd, scenario, "原文片段重叠或不存在");
            previousEnd = start + fragment.length();
            String normalized = fragment.toLowerCase(Locale.ROOT);
            requirePlan(scenario.anchors().get(index).stream()
                    .allMatch(anchor -> normalized.contains(anchor.toLowerCase(Locale.ROOT))), scenario, "诉求或条件遗漏");
            requirePlan(question.path("needs_search").isBoolean()
                    && question.path("needs_search").booleanValue() == scenario.needsSearch().get(index),
                    scenario, "检索需求");
            JsonNode dependencies = question.path("depends_on");
            requirePlan(dependencies.isArray(), scenario, "依赖类型");
            Set<Integer> actual = new HashSet<>();
            for (JsonNode dependency : dependencies) {
                requirePlan(dependency.isInt() && dependency.intValue() > 0
                        && dependency.intValue() <= questions.size() && dependency.intValue() != index + 1
                        && actual.add(dependency.intValue()), scenario, "非法或重复依赖");
            }
            requirePlan(actual.equals(new HashSet<>(scenario.dependencies().get(index))), scenario, "必要依赖判断");
        }
        JsonNode order = plan.path("display_order");
        requirePlan(order.isArray() && order.size() == questions.size(), scenario, "展示顺序数量");
        for (int index = 0; index < order.size(); index++) {
            requirePlan(order.get(index).isInt()
                    && order.get(index).intValue() == scenario.displayOrder().get(index), scenario, "展示顺序");
        }
    }

    private static boolean hasFields(JsonNode node, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length) {
            return false;
        }
        for (String name : names) {
            if (!node.has(name)) {
                return false;
            }
        }
        return true;
    }

    private static void requirePlan(boolean condition, Scenario scenario, String category) {
        if (!condition) {
            throw failure("多问题评测未通过：场景=" + scenario.name() + "，检查项=" + category + "；未打印模型正文");
        }
    }

    record Scenario(String name, String input, List<List<String>> anchors, List<List<Integer>> dependencies,
            List<Boolean> needsSearch, List<Integer> displayOrder) {
    }

    private static String safeStatusMessage(int status) {
        return switch (status) {
            case 401, 403 -> "鉴权失败：请检查中转密钥、分组和模型权限（HTTP " + status + "）";
            case 404, 405 -> "接口路径不可用：请确认基础地址和 Responses 支持情况（HTTP " + status + "）";
            case 400, 422 -> "请求不被接受：请核对模型 ID 与 Responses 参数（HTTP " + status + "）";
            case 402, 429 -> "服务限流或额度不足，请检查中转账户（HTTP " + status + "）";
            default -> "中转服务未正常返回（HTTP " + status + "）；未自动重试";
        };
    }

    private static AssertionError failure(String message) {
        return new AssertionError((Object) message);
    }
}
