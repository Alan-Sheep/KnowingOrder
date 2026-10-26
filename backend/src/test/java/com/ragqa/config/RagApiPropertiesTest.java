package com.ragqa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 仅验证配置，不连接 MySQL，不调用外部 API，也不使用真实凭据。 */
class RagApiPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("rag.search.api-key=", "rag.llm.api-key=",
                    "rag.llm.base-url=", "rag.llm.model=");

    @Test
    void loadsApplicationYamlWithoutCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RagApiProperties properties = context.getBean(RagApiProperties.class);
            assertThat(properties.getSearch().getEndpoint()).isEqualTo("https://api.tavily.com/search");
            assertThat(properties.getSearch().getDepth()).isEqualTo("basic");
            assertThat(properties.getSearch().getMaxResults()).isEqualTo(5);
            assertThat(properties.getSearch().getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.getSearch().getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.getLlm().getReadTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThatThrownBy(properties.getSearch()::requireConfigured)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("TAVILY_API_KEY");
            assertThatThrownBy(properties.getLlm()::requireConfigured)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("DASHSCOPE_BASE_URL");
        });
    }

    @Test
    void acceptsOverridesAndExcludesCredentialsFromOutput() {
        runner.withPropertyValues("rag.search.api-key=test-search-credential",
                "rag.llm.api-key=test-model-credential", "rag.llm.model=test-model",
                "rag.llm.base-url=https://model.example.com/v1",
                "rag.search.max-results=3", "rag.search.read-timeout=7s").run(context -> {
                    assertThat(context).hasNotFailed();
                    RagApiProperties properties = context.getBean(RagApiProperties.class);
                    assertThatCode(properties.getSearch()::requireConfigured).doesNotThrowAnyException();
                    assertThatCode(properties.getLlm()::requireConfigured).doesNotThrowAnyException();
                    assertThat(properties.getSearch().getMaxResults()).isEqualTo(3);
                    assertThat(properties.getSearch().getReadTimeout()).isEqualTo(Duration.ofSeconds(7));
                    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
                    String json = mapper.writeValueAsString(properties);
                    assertThat(json).doesNotContain("apiKey", "test-search-credential", "test-model-credential");
                    assertThat(properties.getSearch().toString()).doesNotContain("test-search-credential");
                    assertThat(properties.getLlm().toString()).doesNotContain("test-model-credential");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"rag.search.max-results=0", "rag.search.max-results=6",
            "rag.search.read-timeout=0s", "rag.llm.connect-timeout=-1s",
            "rag.search.endpoint=not-a-url", "rag.llm.base-url=not-a-url",
            "rag.search.depth=advanced"})
    void rejectsInvalidParameters(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void reportsMissingModelAndCredentialSeparately() {
        RagApiProperties.Llm llm = new RagApiProperties.Llm();
        llm.setBaseUrl("https://model.example.com/v1");
        assertThatThrownBy(llm::requireConfigured).hasMessageContaining("LLM_MODEL");
        llm.setModel("test-model");
        assertThatThrownBy(llm::requireConfigured).hasMessageContaining("DASHSCOPE_API_KEY");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RagApiProperties.class)
    static class PropertiesConfiguration {
    }
}
