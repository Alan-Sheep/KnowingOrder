package com.ragqa.rag.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.llm.PromptRegistry;
import com.ragqa.rag.llm.ResponsesClient;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** 模板调用入口：记录版本与耗时，只接受严格 JSON，不记录输入和模型原始正文。 */
@Slf4j
@Component
public class ChatModel {

    private final ResponsesClient client;
    private final PromptRegistry registry;
    private final ObjectMapper mapper;
    private final ObjectReader reader;

    public ChatModel(ResponsesClient client, PromptRegistry registry, ObjectMapper mapper) {
        this.client = client;
        this.registry = registry;
        this.mapper = mapper;
        this.reader = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    }

    public JsonNode call(String name, Object payload, int tokens, TaskDeadline deadline) {
        PromptRegistry.Prompt prompt = registry.get(name);
        long started = System.nanoTime();
        try {
            String input = prompt.render(mapper.writeValueAsString(payload));
            String output = client.generate(prompt.system(), input, tokens, deadline);
            // 结构化结果只校验一次，不把失败正文回灌模型，不隐式重复计费。
            if (output == null || output.length() > 32000) {
                throw invalid();
            }
            JsonNode result = reader.readValue(output);
            if (result == null || !result.isObject()) {
                throw invalid();
            }
            log.info("{\"event\":\"chat_model_completed\",\"request_id\":\"{}\",\"prompt_id\":\"{}\",\"elapsed_ms\":{}}",
                    MDC.get("request_id"), prompt.id(), (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
    }

    public static BusinessException invalid() {
        return new BusinessException(ResultCode.BIZ_ERROR, "回答数据校验未通过，请调整问题后重试");
    }
}
