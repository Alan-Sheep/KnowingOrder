package com.ragqa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 只使用虚构凭据与模拟服务；验证探测逻辑本身，不表示真实中转已连通。 */
class LlmConnectivityProbeTest {

    private static final String KEY = "fake-credential-sensitive-marker";
    private static final String ENDPOINT = "https://relay.example.com/v1/responses";
    private static final String SUCCESS = """
            {"object":"response","status":"completed","error":null,"output":[
              {"type":"message","role":"assistant","content":[{"type":"output_text","text":"OK"}]}]}
            """;
    private static final String THREE_QUESTIONS = """
            {"questions":[
              {"id":1,"source_text":"晚上放了学回家吃什么","depends_on":[],"needs_search":false},
              {"id":2,"source_text":"要去面试的话需要准备啥","depends_on":[],"needs_search":false},
              {"id":3,"source_text":"agent和java两个哪个就业好？","depends_on":[],"needs_search":true}],
             "display_order":[1,2,3],"needs_clarification":false}
            """;

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://relay.example.com/v1");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestClient client = builder.build();

    @Test
    void sendsExactlyOneBoundedResponsesRequestAndReturnsOnlyLength() {
        server.expect(requestTo(ENDPOINT)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY))
                .andRespond(request -> {
                    var body = new ObjectMapper().readTree(((org.springframework.mock.http.client.MockClientHttpRequest)
                            request).getBodyAsString());
                    assertThat(body.path("model").asText()).isEqualTo("test-model");
                    assertThat(body.path("input").asText()).isEqualTo("请只回复 OK。");
                    assertThat(body.path("max_output_tokens").asInt()).isEqualTo(256);
                    assertThat(body.path("store").asBoolean(true)).isFalse();
                    assertThat(body.path("stream").asBoolean(true)).isFalse();
                    assertThat(body.toString()).doesNotContain(KEY);
                    return withSuccess(SUCCESS, MediaType.APPLICATION_JSON).createResponse(request);
                });

        assertThat(LlmConnectivityIT.probe(client, KEY, "test-model")).isEqualTo(2);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 422, 429, 503})
    void httpFailuresAreSafeAndNeverRetried(int status) {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.valueOf(status))
                .body("原始错误正文:" + KEY).contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> LlmConnectivityIT.probe(client, KEY, "test-model"))
                .isInstanceOf(AssertionError.class).hasNoCause().hasMessageContaining("HTTP " + status)
                .hasMessageNotContaining(KEY).hasMessageNotContaining("原始错误正文");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "<html>私密正文</html>",
            "{\"object\":\"response\",\"status\":\"completed\",\"output\":[]}",
            "{\"object\":\"response\",\"status\":\"incomplete\",\"output\":[]}",
            "{\"object\":\"response\",\"status\":\"completed\",\"error\":{\"message\":\"私密正文\"},\"output\":[]}",
            "{\"object\":\"response\",\"status\":\"completed\",\"output\":null}"})
    void invalidEmptyOrIncompleteResponsesCannotClaimSuccess(String body) {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> LlmConnectivityIT.probe(client, KEY, "test-model"))
                .isInstanceOf(AssertionError.class).hasNoCause().hasMessageNotContaining("私密正文");
        server.verify();
    }

    @Test
    void trailingJsonIsRejectedWithoutEchoingBody() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(SUCCESS + " {\"secret\":\"私密正文\"}",
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> LlmConnectivityIT.probe(client, KEY, "test-model"))
                .isInstanceOf(AssertionError.class).hasNoCause().hasMessageNotContaining("私密正文");
        server.verify();
    }

    @Test
    void nonJsonSuccessIsNotMistakenForModelOutput() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("私密正文", MediaType.TEXT_HTML));
        assertThatThrownBy(() -> LlmConnectivityIT.probe(client, KEY, "test-model"))
                .isInstanceOf(AssertionError.class).hasNoCause().hasMessageNotContaining("私密正文");
        server.verify();
    }

    @Test
    void timeoutDoesNotLeakUnderlyingExceptionOrRetry() {
        server.expect(requestTo(ENDPOINT)).andRespond(withException(new SocketTimeoutException(KEY)));
        assertThatThrownBy(() -> LlmConnectivityIT.probe(client, KEY, "test-model"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("超时").hasNoCause()
                .hasMessageNotContaining(KEY);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "https://relay.example.com/v1?key=私密正文", "http://relay.example.com/v1",
            "https://user:私密正文@relay.example.com/v1", "https://relay.example.com/v1/responses",
            "https://relay.example.com/v1/chat/completions", "https://relay.example.com/v1#私密正文"})
    void invalidBaseUrlsAreRejectedWithoutEchoingCredentials(String baseUrl) {
        assertThatThrownBy(() -> LlmConnectivityIT.validateBaseUrl(baseUrl))
                .isInstanceOf(AssertionError.class).hasNoCause().hasMessageNotContaining("私密正文");
    }

    @Test
    void acceptsConfiguredRelayBaseUrl() {
        LlmConnectivityIT.validateBaseUrl("https://xn--ai-xg4e312c.com/v1");
    }

    @Test
    void analysisSendsRawQuestionWithoutLeakingExpectedAnswersAndReturnsCompletedText() throws Exception {
        var scenario = LlmConnectivityIT.scenarios().get(0);
        String instructions = LlmConnectivityIT.analysisInstructions();
        server.expect(requestTo(ENDPOINT)).andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY))
                .andRespond(request -> {
                    var body = new ObjectMapper().readTree(((org.springframework.mock.http.client.MockClientHttpRequest)
                            request).getBodyAsString());
                    assertThat(body.path("input").size()).isEqualTo(2);
                    assertThat(body.at("/input/0/role").asText()).isEqualTo("system");
                    assertThat(body.at("/input/0/content").asText()).isEqualTo(instructions);
                    assertThat(body.at("/input/1/role").asText()).isEqualTo("user");
                    assertThat(body.at("/input/1/content").asText()).isEqualTo(scenario.input());
                    assertThat(body.path("max_output_tokens").asInt()).isEqualTo(2048);
                    assertThat(body.path("stream").asBoolean(true)).isFalse();
                    assertThat(body.path("store").asBoolean(true)).isFalse();
                    assertThat(body.path("model").asText()).isEqualTo("test-model");
                    assertThat(body.toString()).doesNotContain(KEY, "anchors", "无标点混合三问");
                    return withSuccess(responseWithText(THREE_QUESTIONS), MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });

        String analysis = LlmConnectivityIT.analyze(client, KEY, "test-model", instructions, scenario.input(), null);
        assertThat(analysis).isEqualTo(THREE_QUESTIONS);
        LlmConnectivityIT.verifyAnalysis(analysis, scenario);
        server.verify();
    }

    @Test
    void textPiecesPreserveMeaningfulWhitespace() throws Exception {
        ObjectNode response = (ObjectNode) new ObjectMapper().readTree(SUCCESS);
        ArrayNode content = (ArrayNode) response.at("/output/0/content");
        content.removeAll();
        for (String piece : new String[]{"Java", " ", "面试"}) {
            content.addObject().put("type", "output_text").put("text", piece);
        }
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        assertThat(LlmConnectivityIT.analyze(client, KEY, "test-model", "固定评测规则", "固定测试输入", null))
                .isEqualTo("Java 面试");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t "})
    void blankCompletedTextCannotClaimSuccess(String text) throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(responseWithText(text), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> LlmConnectivityIT.probe(client, KEY, "test-model"))
                .isInstanceOf(AssertionError.class).hasNoCause().hasMessageContaining("没有可用文本");
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("goldenPlans")
    void acceptsManuallyReviewedScenarioPlans(int index, String plan) {
        LlmConnectivityIT.verifyAnalysis(plan, LlmConnectivityIT.scenarios().get(index));
    }

    static Stream<Arguments> goldenPlans() {
        return Stream.of(
                Arguments.of(0, THREE_QUESTIONS),
                Arguments.of(1, """
                        {"questions":[{"id":1,"source_text":"推荐一顿晚饭，预算20元，十分钟做好，不吃辣。",
                         "depends_on":[],"needs_search":false}],"display_order":[1],"needs_clarification":false}
                        """),
                Arguments.of(2, """
                        {"questions":[
                         {"id":1,"source_text":"推荐晚饭","depends_on":[],"needs_search":false},
                         {"id":2,"source_text":"告诉我Java面试准备","depends_on":[],"needs_search":false},
                         {"id":3,"source_text":"比较Agent和Java就业方向","depends_on":[],"needs_search":true}],
                         "display_order":[3,2,1],"needs_clarification":false}
                        """),
                Arguments.of(3, """
                        {"questions":[
                         {"id":1,"source_text":"先查北京Java和Agent岗位的最新要求","depends_on":[],"needs_search":true},
                         {"id":2,"source_text":"再根据这些要求列出我的面试准备清单","depends_on":[1],"needs_search":false},
                         {"id":3,"source_text":"最后给我一道晚饭建议","depends_on":[],"needs_search":false}],
                         "display_order":[1,2,3],"needs_clarification":false}
                        """));
    }

    @ParameterizedTest
    @ValueSource(strings = {"漏掉晚饭", "多拆一个问题", "要求用户排序", "澄清布尔值变字符串", "伪造原文",
            "原文重叠", "问题编号重复", "编号为小数", "凭空增加依赖", "自依赖", "依赖越界", "依赖为字符串",
            "重复依赖", "展示遗漏", "展示重复", "展示顺序错误", "就业不检索", "检索布尔值变字符串",
            "增加未知字段", "缺失字段", "子问题为null"})
    void faultyPlansFailWithoutPrintingModelContent(String mutation) throws Exception {
        ObjectNode plan = (ObjectNode) new ObjectMapper().readTree(THREE_QUESTIONS);
        ArrayNode questions = (ArrayNode) plan.path("questions");
        ObjectNode first = (ObjectNode) questions.get(0);
        ObjectNode second = (ObjectNode) questions.get(1);
        ObjectNode third = (ObjectNode) questions.get(2);
        switch (mutation) {
            case "漏掉晚饭" -> questions.remove(0);
            case "多拆一个问题" -> questions.add(first.deepCopy());
            case "要求用户排序" -> plan.put("needs_clarification", true);
            case "澄清布尔值变字符串" -> plan.put("needs_clarification", "false");
            case "伪造原文" -> first.put("source_text", KEY);
            case "原文重叠" -> first.put("source_text", LlmConnectivityIT.scenarios().get(0).input());
            case "问题编号重复" -> second.put("id", 1);
            case "编号为小数" -> first.put("id", 1.0);
            case "凭空增加依赖" -> second.putArray("depends_on").add(3);
            case "自依赖" -> first.putArray("depends_on").add(1);
            case "依赖越界" -> first.putArray("depends_on").add(4);
            case "依赖为字符串" -> first.putArray("depends_on").add("2");
            case "重复依赖" -> first.putArray("depends_on").add(2).add(2);
            case "展示遗漏" -> plan.putArray("display_order").add(1).add(2);
            case "展示重复" -> plan.putArray("display_order").add(1).add(2).add(2);
            case "展示顺序错误" -> plan.putArray("display_order").add(3).add(2).add(1);
            case "就业不检索" -> third.put("needs_search", false);
            case "检索布尔值变字符串" -> first.put("needs_search", "false");
            case "增加未知字段" -> plan.put("model_output", KEY);
            case "缺失字段" -> second.remove("depends_on");
            case "子问题为null" -> questions.set(0, plan.nullNode());
            default -> throw new IllegalArgumentException("未知测试变体");
        }
        assertThatThrownBy(() -> LlmConnectivityIT.verifyAnalysis(plan.toString(), LlmConnectivityIT.scenarios().get(0)))
                .isInstanceOf(AssertionError.class).hasNoCause().hasMessageContaining("检查项=")
                .hasMessageNotContaining(KEY).hasMessageNotContaining("source_text")
                .hasMessageNotContaining("depends_on");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{}", "```json\n{}\n```", "{} {}",
            "{\"questions\":[],\"questions\":[]}", "{\"secret\":\"fake-credential-sensitive-marker\"}"})
    void malformedPlansNeverEchoParserExceptions(String plan) {
        assertThatThrownBy(() -> LlmConnectivityIT.verifyAnalysis(plan, LlmConnectivityIT.scenarios().get(0)))
                .isInstanceOf(AssertionError.class).hasNoCause().hasMessageNotContaining(KEY);
    }

    @Test
    void missingSingleQuestionConstraintsAreNotAccepted() throws Exception {
        String plan = """
                {"questions":[{"id":1,"source_text":"推荐一顿晚饭","depends_on":[],"needs_search":false}],
                 "display_order":[1],"needs_clarification":false}
                """;
        assertThatThrownBy(() -> LlmConnectivityIT.verifyAnalysis(plan, LlmConnectivityIT.scenarios().get(1)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("诉求或条件遗漏");
    }

    @Test
    void requiredDependencyCannotBeDropped() throws Exception {
        String plan = (String) goldenPlans().toList().get(3).get()[1];
        ObjectNode parsed = (ObjectNode) new ObjectMapper().readTree(plan);
        ((ObjectNode) parsed.path("questions").get(1)).putArray("depends_on");
        assertThatThrownBy(() -> LlmConnectivityIT.verifyAnalysis(parsed.toString(), LlmConnectivityIT.scenarios().get(3)))
                .isInstanceOf(AssertionError.class).hasMessageContaining("必要依赖判断");
    }

    @Test
    void expiredBatchBudgetPreventsSendingAnalysis() {
        var http = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        RestClient bounded = RestClient.builder().baseUrl("https://relay.example.com/v1")
                .requestFactory(new BoundedJdkClientHttpRequestFactory(http, Duration.ofSeconds(60), 2048)).build();
        var deadline = com.ragqa.rag.chat.TaskDeadline.start(Duration.ofNanos(1));
        assertThatThrownBy(() -> LlmConnectivityIT.analyze(bounded, KEY, "test-model",
                LlmConnectivityIT.analysisInstructions(), "晚饭吃什么", deadline))
                .isInstanceOf(AssertionError.class).hasNoCause().hasMessageContaining("超时");
        org.mockito.Mockito.verifyNoInteractions(http);
    }

    private static String responseWithText(String text) throws IOException {
        ObjectNode response = (ObjectNode) new ObjectMapper().readTree(SUCCESS);
        ((ObjectNode) response.at("/output/0/content/0")).put("text", text);
        return response.toString();
    }
}
