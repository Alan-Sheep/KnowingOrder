package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.config.RagApiProperties;
import com.ragqa.config.RagHttpClientConfig;
import com.ragqa.config.WebChatProperties;
import com.ragqa.domain.dto.ChatAnswer;
import com.ragqa.domain.dto.SearchResult;
import com.ragqa.rag.llm.PromptRegistry;
import com.ragqa.rag.llm.ResponsesClient;
import com.ragqa.rag.retrieval.EvidenceSelector;
import com.ragqa.rag.retrieval.TavilyClient;
import com.ragqa.rag.retrieval.TavilyResultMapper;
import com.ragqa.service.WebChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 付费接口手动验收；IT 后缀和独立开关防止普通构建意外调用。 */
@Timeout(105)
public class WebChatLiveIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPT_FLOW_IT", matches = "true")
    void configuredGptRunsProductionPromptsWithControlledEvidence() {
        try (WebChatService service = service(false)) {
            ChatAnswer answer = service.answer("请联网核对 Java 17 的发布日期和长期支持性质。");
            assertThat(answer.status()).isEqualTo(ChatAnswer.Status.COMPLETED);
            assertThat(answer.answers()).hasSize(1);
            assertThat(answer.answers().get(0).basis()).isEqualTo(ChatAnswer.Basis.WEB);
            assertThat(answer.answers().get(0).answer()).contains("[q1-s");
            System.out.println("GPT 正式闭环模板验证通过：问题数=1，引用校验通过；搜索为受控测试资料，未访问真实搜索服务");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GPT_FLOW_IT", matches = "true")
    void configuredGptKeepsAllThreeMixedRequestsWithoutPriorityClarification() {
        try (WebChatService service = service(false)) {
            ChatAnswer answer = service.answer("晚上放了学回家吃什么要去面试的话需要准备啥agent和java两个哪个就业好？");
            assertThat(answer.answers()).hasSize(3);
            assertThat(answer.answers().get(0).question()).contains("吃什么");
            assertThat(answer.answers().get(1).question()).contains("面试");
            assertThat(answer.answers().get(2).question().toLowerCase(java.util.Locale.ROOT)).contains("agent", "java");
            assertThat(answer.answers()).noneSatisfy(part ->
                    assertThat(part.status()).isEqualTo(ChatAnswer.PartStatus.CLARIFICATION_NEEDED));
            assertThat(answer.answers().get(0).status()).isEqualTo(ChatAnswer.PartStatus.ANSWERED);
            assertThat(answer.answers().get(1).status()).isEqualTo(ChatAnswer.PartStatus.ANSWERED);
            assertThat(answer.answers().get(2).status()).isEqualTo(ChatAnswer.PartStatus.INSUFFICIENT_EVIDENCE);
            System.out.println("GPT 混合三问闭环验证通过：三项诉求保留，通用建议完成；就业结论因测试证据不足明确停止，未要求用户选择优先级");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_WEB_CHAT_LIVE_TEST", matches = "true")
    void configuredSearchAndGptCompleteRealOnlineQuestion() {
        try (WebChatService service = service(true)) {
            ChatAnswer answer = service.answer("请联网核对 Java 17 的发布日期和长期支持性质，优先参考官方资料。");
            assertThat(answer.status()).isEqualTo(ChatAnswer.Status.COMPLETED);
            assertThat(answer.answers().get(0).sources()).isNotEmpty();
            assertThat(answer.answers().get(0).answer()).contains("[q1-s");
            System.out.println("真实联网问答验收通过：生产搜索与模型客户端均参与，引用映射通过");
        }
    }

    static WebChatService service(boolean realSearch) {
        RagApiProperties properties = new RagApiProperties();
        properties.getLlm().setBaseUrl(required("DASHSCOPE_BASE_URL"));
        properties.getLlm().setApiKey(required("DASHSCOPE_API_KEY"));
        properties.getLlm().setModel(required("LLM_MODEL"));
        ObjectMapper json = new ObjectMapper();
        RagHttpClientConfig config = new RagHttpClientConfig();
        ChatModel model = new ChatModel(new ResponsesClient(config.llmRestClient(RestClient.builder(), properties),
                properties, json), new PromptRegistry(), json);
        TavilyClient search;
        if (realSearch) {
            properties.getSearch().setApiKey(required("TAVILY_API_KEY"));
            search = new TavilyClient(config.searchRestClient(RestClient.builder(), properties), properties,
                    new TavilyResultMapper(), json);
        } else {
            search = mock(TavilyClient.class);
            when(search.search(anyString(), any())).thenAnswer(invocation -> {
                String query = invocation.getArgument(0);
                if (query.contains("Java 17") || query.contains("Java17") || query.contains("JDK 17")) {
                    return List.of(new SearchResult("JDK 17 受控测试资料", "https://openjdk.org/projects/jdk/17/",
                            "本次测试资料：JDK 17 于 2021 年 9 月 14 日发布，Java 17 是长期支持版本。", 0.9));
                }
                return List.of(new SearchResult("就业比较的故障测试资料", "https://example.com/fixture-only",
                        "本资料仅用于测试证据不足分支，不包含任何地区的 Agent 或 Java 岗位数量、薪资、就业趋势或比较结论。", 0.8));
            });
        }
        WebChatProperties chat = new WebChatProperties();
        return new WebChatService(new QuestionPlanner(model, chat), search,
                new EvidenceSelector(), new AnswerComposer(model), chat);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new AssertionError("缺少运行环境变量 " + name + "；不读取或打印配置文件中的密钥");
        }
        return value;
    }
}
