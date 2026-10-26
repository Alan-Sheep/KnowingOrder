package com.ragqa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.config.LocalChatFilter;
import com.ragqa.controller.ProductSearchController;
import com.ragqa.domain.dto.ProductSearchResult;
import com.ragqa.exception.BusinessException;
import com.ragqa.exception.GlobalExceptionHandler;
import com.ragqa.service.impl.MockProductSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 使用真实Mock适配器、MVC校验和开发保护器，不访问数据库、模型或淘宝。 */
class ProductSearchTest {

    private final ProductSearchService service = new MockProductSearchService();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProductSearchController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new LocalChatFilter(new ObjectMapper())).build();

    @Test
    void mockSearchHasExplicitProvenanceAndFixedSafeLinks() throws Exception {
        var result = service.search(" 请帮我找 COFFEE ");
        assertThat(result.provider()).isEqualTo("MOCK");
        assertThat(result.simulated()).isTrue();
        assertThat(result.notice()).contains("模拟", "搜索", "不对应本系统的示例商品");
        assertThat(result.items()).hasSize(2).allSatisfy(item -> {
            assertThat(item.id()).startsWith("mock-");
            assertThat(item.referencePrice()).isPositive();
            assertThat(item.currency()).isEqualTo("CNY");
            assertThat(item.linkType()).isEqualTo("PLATFORM_SEARCH");
            URI uri = URI.create(item.jumpUrl());
            assertThat(uri.getScheme()).isEqualTo("https");
            // 跳转是淘宝搜索页而不是首页：示例商品是虚构的，不存在可直达的真实详情页。
            assertThat(uri.getHost()).isEqualTo("s.taobao.com");
            assertThat(uri.getPath()).isEqualTo("/search");
            assertThat(uri.getUserInfo()).isNull();
            // 查询串只允许一个 q，且必须是本条目自己的关键词，不能夹带其它参数。
            assertThat(uri.getRawQuery()).startsWith("q=");
            assertThat(uri.getRawQuery()).doesNotContain("&");
            String term = java.net.URLDecoder.decode(uri.getRawQuery().substring(2), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(term).isNotBlank().doesNotContain("（示例）");
        });
        assertThatThrownBy(() -> result.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        mvc.perform(post("/api/products/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"咖啡\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.simulated").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"奶茶", "晚餐", "拿铁"})
    void onlyReturnsMatchingCategory(String keyword) {
        assertThat(service.search(keyword).items()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"不存在商品", "https://evil.example/", "' OR 1=1 --", "<script>alert(1)</script>"})
    void unmatchedOrExecutableLookingInputDoesNotBecomeLinkOrQuery(String keyword) {
        assertThat(service.search(keyword).items()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\n", "咖啡\u0000"})
    void serviceRejectsInvalidInputWithoutEchoingIt(String keyword) {
        assertThatThrownBy(() -> service.search(keyword)).isInstanceOf(BusinessException.class)
                .hasMessage("请输入1至80个字符的商品关键词，不能包含控制字符");
    }

    @Test
    void enforcesLengthEvenWhenCalledOutsideController() {
        assertThat(service.search("咖啡" + "字".repeat(78)).items()).hasSize(2);
        assertThatThrownBy(() -> service.search("咖啡" + "字".repeat(79)))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"keyword\":null}", "{\"keyword\":\" \"}", "not-json"})
    void invalidHttpBodyUsesExistingErrorContract(String body) throws Exception {
        mvc.perform(post("/api/products/search").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void oversizedBodyAndKeywordAreBounded() throws Exception {
        mvc.perform(post("/api/products/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keyword\":\"" + "字".repeat(81) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/products/search").contentType(MediaType.APPLICATION_JSON).content("x".repeat(8193)))
                .andExpect(status().isPayloadTooLarge());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/products/search", "/api/products;v=1/search", "/api/%70roducts/search"})
    void remoteRequestsCannotBypassGuard(String path) throws Exception {
        mvc.perform(post(URI.create(path)).with(request -> { request.setRemoteAddr("203.0.113.1"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"咖啡\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void forwardedRequestsAreRejected() throws Exception {
        mvc.perform(post("/api/products/search").header("Forwarded", "for=127.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"keyword\":\"咖啡\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentSearchesDoNotShareMutableResults() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<ProductSearchResult>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                String keyword = i % 2 == 0 ? "咖啡" : "奶茶";
                calls.add(() -> service.search(keyword));
            }
            var results = executor.invokeAll(calls, 3, TimeUnit.SECONDS);
            for (int i = 0; i < results.size(); i++) {
                assertThat(results.get(i).get().items()).hasSize(i % 2 == 0 ? 2 : 1);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Mock 适配器不再绑定 dev：它对环境无副作用（不查库、不发外部请求、不产生订单），
     * prod 下同样装配，否则只开放 IP 演示的环境里商品查询会整个消失。
     * 但 provider 一旦被改成 taobao，它必须彻底不装配，不能悄悄回退成假数据。
     */
    @Test
    void mockIsProfileAgnosticButNeverFallsBackFromRealMode() {
        var runner = new ApplicationContextRunner().withUserConfiguration(
                MockProductSearchService.class, ProductSearchController.class);
        for (String profile : List.of("dev", "prod")) {
            runner.withPropertyValues("spring.profiles.active=" + profile, "knowingorder.products.provider=mock")
                    .run(context -> {
                        assertThat(context).hasSingleBean(ProductSearchService.class);
                        assertThat(context).hasSingleBean(ProductSearchController.class);
                        assertThat(context.getBean(ProductSearchService.class))
                                .isInstanceOf(MockProductSearchService.class);
                    });
        }
        runner.withPropertyValues("spring.profiles.active=prod", "knowingorder.products.provider=taobao")
                .run(context -> assertThat(context).hasFailed());
    }
}
