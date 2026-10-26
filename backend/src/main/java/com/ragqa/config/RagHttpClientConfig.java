package com.ragqa.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 外部 API 的 HTTP 传输配置，不负责请求参数、鉴权、重试或业务降级。
 *
 * <p>两个客户端分别复用连接并使用各自的超时；调用方通过 Bean 名称限定注入。
 * 凭据由具体客户端在检查配置后按请求添加，不在共享构建器上设置。</p>
 */
@Configuration(proxyBeanMethods = false)
public class RagHttpClientConfig {

    /** 普通 JSON 响应的初始保护值；按字节限额缓冲，避免异常响应无限占用内存。 */
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    @Bean
    public RestClient searchRestClient(RestClient.Builder builder, RagApiProperties properties) {
        RagApiProperties.Search search = properties.getSearch();
        return buildClient(builder, search.getEndpoint(), search.getConnectTimeout(), search.getReadTimeout());
    }

    @Bean
    public RestClient llmRestClient(RestClient.Builder builder, RagApiProperties properties) {
        RagApiProperties.Llm llm = properties.getLlm();
        return buildClient(builder, llm.getBaseUrl(), llm.getConnectTimeout(), llm.getReadTimeout());
    }

    private RestClient buildClient(RestClient.Builder builder, String baseUrl,
            Duration connectTimeout, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                // 显式禁止重定向，后续带凭据的请求也不自动转发到其他地址。
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        // 时限覆盖发送到正文接收完成，持续收到少量数据也不会重置计时。
        BoundedJdkClientHttpRequestFactory factory =
                new BoundedJdkClientHttpRequestFactory(httpClient, readTimeout, MAX_RESPONSE_BYTES);
        // 克隆容器提供的构建器，保留消息转换配置，隔离两个客户端的地址和请求工厂。
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
