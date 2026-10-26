package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.config.BoundedJdkClientHttpRequestFactory;
import com.ragqa.config.LocalChatFilter;
import com.ragqa.config.RagApiProperties;
import com.ragqa.config.WebChatProperties;
import com.ragqa.controller.WebChatController;
import com.ragqa.exception.GlobalExceptionHandler;
import com.ragqa.rag.llm.PromptRegistry;
import com.ragqa.rag.llm.ResponsesClient;
import com.ragqa.rag.retrieval.EvidenceSelector;
import com.ragqa.rag.retrieval.TavilyClient;
import com.ragqa.rag.retrieval.TavilyResultMapper;
import com.ragqa.service.WebChatService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 接口、真实生产编排和 HTTP 客户端全链路运行；只有第三方服务由本地 HTTP 替代。 */
@Timeout(15)
class WebChatHttpTest {
    @Test
    void controllerToSearchAndResponsesReturnsVerifiedAnswer() throws Exception {
        try (Fixture f = new Fixture(200, false)) {
            f.mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"请联网查岗位要求\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.data.answers[0].answer").value("岗位要求 Java 17。 [q1-s1]"))
                    .andExpect(jsonPath("$.data.answers[0].sources[0].id").value("q1-s1"));
            assertThat(f.searchCalls).hasValue(1);
            assertThat(f.modelCalls).hasValue(3);
        }
    }

    @ParameterizedTest
    @CsvSource({"401,1", "429,1", "503,2"})
    void externalFailuresAreBoundedAndVisibleInActualInterface(int upstreamStatus, int attempts) throws Exception {
        try (Fixture f = new Fixture(upstreamStatus, false)) {
            var result = f.mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"请联网查岗位要求\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NO_ANSWER"))
                    .andExpect(jsonPath("$.data.answers[0].status").value("FAILED")).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("fake-upstream-secret");
            assertThat(f.searchCalls).hasValue(attempts);
            assertThat(f.modelCalls).hasValue(1);
        }
    }

    @Test
    void genuineEmptySearchEndsWithoutGeneratingUnsupportedAnswer() throws Exception {
        try (Fixture f = new Fixture(200, true)) {
            f.mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"请联网查未知事项\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.answers[0].status").value("NO_EVIDENCE"));
            assertThat(f.modelCalls).hasValue(1);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper json = new ObjectMapper();
        final HttpServer server;
        final ExecutorService executor = Executors.newFixedThreadPool(4);
        final AtomicInteger modelCalls = new AtomicInteger();
        final AtomicInteger searchCalls = new AtomicInteger();
        final WebChatService service;
        final MockMvc mvc;

        Fixture(int searchStatus, boolean empty) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/search", exchange -> {
                searchCalls.incrementAndGet();
                assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fake-search-key");
                JsonNode query = json.readTree(exchange.getRequestBody());
                assertThat(query.path("include_answer").booleanValue()).isFalse();
                respond(exchange, searchStatus, searchStatus != 200 ? Map.of("error", "fake-upstream-secret")
                        : Map.of("results", empty ? List.of() : List.of(Map.of("title", "岗位说明", "url",
                        "https://example.com/job", "content", "岗位要求 Java 17。", "score", 0.9))));
            });
            server.createContext("/v1/responses", exchange -> {
                modelCalls.incrementAndGet();
                JsonNode request = json.readTree(exchange.getRequestBody());
                assertThat(request.path("stream").booleanValue()).isFalse();
                String rules = request.at("/input/0/content").asText();
                String input = request.at("/input/1/content").asText();
                JsonNode payload = json.readTree(input.substring(input.indexOf('{')));
                Object result;
                if (rules.contains("诉求分析器")) {
                    String question = payload.path("question").asText();
                    result = Map.of("overflow", false, "questions", List.of(Map.of("id", 1, "question", question,
                            "query", question, "needsSearch", true, "dependsOn", List.of(), "clarification", "")),
                            "displayOrder", List.of(1));
                } else if (rules.contains("资料充分性评估器")) {
                    result = Map.of("verdict", "SUFFICIENT", "facts", List.of(Map.of("sourceId", 1,
                            "quote", "Java 17", "text", "岗位要求 Java 17。")), "gaps", List.of(), "followUpQuery", "");
                } else {
                    result = Map.of("paragraphs", List.of(Map.of("text", "岗位要求 Java 17。", "sourceIds", List.of(1))));
                }
                respond(exchange, 200, Map.of("object", "response", "status", "completed", "output", List.of(
                        Map.of("type", "message", "role", "assistant", "status", "completed", "content", List.of(
                                Map.of("type", "output_text", "text", json.writeValueAsString(result)))))));
            });
            server.start();
            RagApiProperties properties = new RagApiProperties();
            properties.getSearch().setApiKey("fake-search-key");
            properties.getLlm().setApiKey("fake-model-key");
            properties.getLlm().setBaseUrl("https://relay.invalid/v1");
            properties.getLlm().setModel("fixture-model");
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            RestClient searchHttp = http(endpoint + "/search");
            RestClient modelHttp = http(endpoint + "/v1");
            ChatModel model = new ChatModel(new ResponsesClient(modelHttp, properties, json), new PromptRegistry(), json);
            WebChatProperties chat = new WebChatProperties();
            chat.setTotalTimeout(Duration.ofSeconds(6));
            service = new WebChatService(new QuestionPlanner(model, chat), new TavilyClient(searchHttp, properties,
                    new TavilyResultMapper(), json), new EvidenceSelector(), new AnswerComposer(model), chat);
            mvc = MockMvcBuilders.standaloneSetup(new WebChatController(service))
                    .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new LocalChatFilter(json)).build();
        }

        private RestClient http(String baseUrl) {
            return RestClient.builder().baseUrl(baseUrl).requestFactory(new BoundedJdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(), Duration.ofSeconds(3),
                    2 * 1024 * 1024)).build();
        }

        private void respond(HttpExchange exchange, int status, Object data) throws IOException {
            byte[] bytes = json.writeValueAsBytes(data);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        @Override
        public void close() {
            service.close();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
