package com.ragqa.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用本机随机端口验证真实 HTTP 行为，不访问外网、不使用真实凭据。 */
@Timeout(10)
class RagHttpClientConfigTest {

    private HttpServer server;
    private ExecutorService workers;
    private String baseUrl;
    private final CountDownLatch releaseSlowRequest = new CountDownLatch(1);
    private final AtomicInteger unauthorizedRequests = new AtomicInteger();
    private final AtomicInteger redirectedRequests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext("/search", exchange -> respond(exchange, 200, "search-ok"));
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, 200, "model-ok"));
        server.createContext("/slow", exchange -> {
            try {
                releaseSlowRequest.await(5, TimeUnit.SECONDS);
                respond(exchange, 200, "late");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.createContext("/unauthorized", exchange -> {
            unauthorizedRequests.incrementAndGet();
            respond(exchange, 401, "unauthorized");
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirectedRequests.incrementAndGet();
            respond(exchange, 200, "redirected");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        releaseSlowRequest.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (workers != null) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void clientsKeepIndependentAddressesAndAcceptJson() {
        server.createContext("/headers", exchange -> respond(exchange, 200,
                exchange.getRequestHeaders().getFirst("Accept")));
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            RestClient search = context.getBean("searchRestClient", RestClient.class);
            RestClient llm = context.getBean("llmRestClient", RestClient.class);
            assertThat(search).isNotSameAs(llm);
            assertThat(search.get().retrieve().body(String.class)).isEqualTo("search-ok");
            assertThat(llm.post().uri("/chat/completions").retrieve().body(String.class))
                    .isEqualTo("model-ok");
            assertThat(search.get().uri(baseUrl + "/headers").retrieve().body(String.class))
                    .isEqualTo("application/json");
        });
    }

    @Test
    void searchReadTimeoutIsAppliedToRealRequest() {
        runner().run(context -> assertThatThrownBy(() -> context.getBean("searchRestClient", RestClient.class)
                        .get().uri(baseUrl + "/slow").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class).hasRootCauseInstanceOf(TimeoutException.class));
    }

    @Test
    void modelReadTimeoutIsAppliedToRealRequest() {
        runner().run(context -> assertThatThrownBy(() -> context.getBean("llmRestClient", RestClient.class)
                        .get().uri(baseUrl + "/slow").retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class).hasRootCauseInstanceOf(TimeoutException.class));
    }

    @Test
    void unauthorizedResponseIsNotRetried() {
        runner().run(context -> {
            assertThatThrownBy(() -> context.getBean("searchRestClient", RestClient.class)
                    .get().uri(baseUrl + "/unauthorized").retrieve().body(String.class))
                    .isInstanceOf(HttpClientErrorException.Unauthorized.class);
            assertThat(unauthorizedRequests).hasValue(1);
        });
    }

    @Test
    void redirectIsReturnedWithoutFollowingTarget() {
        runner().run(context -> {
            assertThat(context.getBean("searchRestClient", RestClient.class)
                    .get().uri(baseUrl + "/redirect").retrieve().toBodilessEntity().getStatusCode())
                    .isEqualTo(HttpStatus.FOUND);
            assertThat(redirectedRequests).hasValue(0);
        });
    }

    private ApplicationContextRunner runner() {
        // 只在测试中直接注入本机 HTTP 地址，不放宽生产配置要求的 HTTPS 校验。
        RagApiProperties properties = new RagApiProperties();
        properties.getSearch().setEndpoint(baseUrl + "/search");
        properties.getLlm().setBaseUrl(baseUrl + "/v1");
        properties.getSearch().setReadTimeout(Duration.ofMillis(300));
        properties.getLlm().setReadTimeout(Duration.ofMillis(600));
        return new ApplicationContextRunner()
                .withBean(RagApiProperties.class, () -> properties)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withUserConfiguration(RagHttpClientConfig.class);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain;charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
