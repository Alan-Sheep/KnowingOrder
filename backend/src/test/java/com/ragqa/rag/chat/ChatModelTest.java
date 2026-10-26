package com.ragqa.rag.chat;

import com.ragqa.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatModelTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{\"a\":1,\"a\":2}", "{} {}", "```json\n{}\n```", "upstream-fake-secret"})
    void rejectsMalformedOutputWithoutRetryingOrExposingIt(String output) {
        try (ChatTestFixture f = new ChatTestFixture()) {
            doReturn(output).when(f.client).generate(anyString(), anyString(), anyInt(), any());
            assertThatThrownBy(() -> f.model.call("plan", Map.of("question", "问题"), 100,
                    TaskDeadline.start(Duration.ofSeconds(5)))).isInstanceOf(BusinessException.class)
                    .hasMessageNotContaining("upstream-fake-secret");
            verify(f.client, times(1)).generate(anyString(), anyString(), anyInt(), any());
        }
    }

    @Test
    void sharedCallLedgerDoesNotRenewItsLimits() {
        try (ChatTestFixture f = new ChatTestFixture()) {
            f.properties.setMaxModelCalls(1);
            f.properties.setMaxSearchCalls(1);
            ChatBudget budget = new ChatBudget(f.properties);
            budget.beforeModel();
            budget.beforeSearch();
            assertThatThrownBy(budget::beforeModel).isInstanceOf(BusinessException.class).hasMessageContaining("额度");
            assertThatThrownBy(budget::beforeSearch).isInstanceOf(BusinessException.class).hasMessageContaining("额度");
        }
    }
}
