package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources.KnowledgeSpace;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.ChatModel;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.service.EnterpriseMemberService;
import com.ragqa.service.EnterpriseResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewedQueryExpansionTest {
    private final QueryRewriter rewriter = mock(QueryRewriter.class);
    private final KnowledgeRetriever retriever = mock(KnowledgeRetriever.class);
    private final ChatModel model = mock(ChatModel.class);
    private final EnterpriseMemberService members = mock(EnterpriseMemberService.class);
    private final EnterpriseResourceService resources = mock(EnterpriseResourceService.class);
    private final EnterpriseMemberContext member = new EnterpriseMemberContext(1, 101, 25, null, "EMPLOYEE", 1, 1, 1);
    private final Evidence original = new Evidence(1L, 2L, 3L, 1, "制度甲", 0, "原始资料。", 0, 5, 1, 1);
    private final Evidence extra = new Evidence(4L, 5L, 6L, 1, "制度乙", 0, "补充资料。", 0, 5, 1, 1);
    private final ObjectMapper json = new ObjectMapper();
    private String mutation = "PASS";
    private ReviewedQueryExpansion create(boolean enabled) { return new ReviewedQueryExpansion(enabled, rewriter, retriever, model, members, resources); }
    private List<Evidence> run() { return create(true).expand(member, 401, "休假后返岗手续？", List.of(original), TaskDeadline.start(Duration.ofSeconds(30))); }
    @BeforeEach void setup() {
        when(members.requireCurrent(member)).thenReturn(member);
        when(resources.requireSpace(member, 401L)).thenReturn(new KnowledgeSpace(401L, "合成空间", 1));
        when(rewriter.rewrite(eq(member), eq(401L), anyString(), any())).thenReturn(new QueryRewriter.Outcome(QueryRewriter.Status.CANDIDATE, "休假结束返回岗位需要办理哪些手续？", "候选"));
        when(retriever.retrieve(eq(member), eq(401L), anyString(), any())).thenReturn(List.of(extra));
        when(retriever.requireEvidence(member, 401, 1)).thenReturn(original);
        when(retriever.requireEvidence(member, 401, 4)).thenReturn(extra);
        when(model.call(anyString(), any(), anyInt(), any())).thenAnswer(call -> {
            Map<?, ?> input = call.getArgument(1);
            var root = json.createObjectNode().put("snapshotId", mutation.equals("snapshot") ? "other" : input.get("snapshotId").toString())
                    .put("meaning", List.of("PASS", "BLOCK", "UNKNOWN").contains(mutation) ? mutation : "PASS");
            var items = root.putArray("items");
            if (!mutation.equals("missing")) items.addObject().put("chunkId", mutation.equals("unknownId") ? "999" : "4")
                    .put("applicability", List.of("REJECT", "UNKNOWN_ITEM").contains(mutation) ? mutation.equals("REJECT") ? "REJECT" : "UNKNOWN" : "ACCEPT");
            if (mutation.equals("duplicate")) items.add(items.get(0).deepCopy());
            return root;
        });
    }
    @Test void defaultOffSkipsEveryDependency() {
        assertThat(create(false).expand(member, 401, "原问题", List.of(original), TaskDeadline.start(Duration.ofSeconds(10))))
                .containsExactly(original);
        verifyNoInteractions(rewriter, retriever, model, members, resources);
    }
    @Test void acceptedReviewSupplementsRatherThanReplacingOriginalEvidence() {
        assertThat(run()).containsExactly(original, extra);
        verify(retriever).retrieve(eq(member), eq(401L), eq("休假结束返回岗位需要办理哪些手续？"), any());
        verify(model).call(eq("rewrite-review"), any(), eq(1200), any());
    }
    @ParameterizedTest @ValueSource(strings = {"BLOCK", "UNKNOWN", "REJECT", "UNKNOWN_ITEM", "snapshot", "unknownId", "duplicate", "missing"})
    void rejectedUnknownAndUnboundReviewsPreserveBaseline(String value) {
        mutation = value;
        assertThat(run()).containsExactly(original);
        verify(model, times(1)).call(anyString(), any(), anyInt(), any());
        verify(retriever).requireEvidence(member, 401, 4);
    }
    @Test void failureDoesNotRetryButRevocationIsNotSwallowedAsBaselineSuccess() {
        doAnswer(call -> {
            when(members.requireCurrent(member)).thenThrow(new BusinessException(com.ragqa.common.ResultCode.NOT_FOUND));
            throw new BusinessException("FAKE_PROVIDER_SECRET");
        }).when(model).call(anyString(), any(), anyInt(), any());
        assertThatThrownBy(this::run).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        verify(model, times(1)).call(anyString(), any(), anyInt(), any());
    }
    @Test void changedEvidenceCannotEnterTheAnswerEvenWhenReviewAcceptedIt() {
        when(retriever.requireEvidence(member, 401, 4)).thenReturn(original);
        assertThatThrownBy(this::run).isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(409));
    }
}
