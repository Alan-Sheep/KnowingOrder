package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.ChatModel;
import com.ragqa.rag.chat.FollowUpResolver;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.service.EnterpriseMemberService;
import com.ragqa.service.EnterpriseResourceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 查询扩展只补充候选，不替换用户问题；语义审查是风险控制，不能证明改写绝对等价。 */
@Component
public final class ReviewedQueryExpansion {
    private final boolean enabled;
    private final QueryRewriter rewriter;
    private final KnowledgeRetriever retriever;
    private final ChatModel model;
    private final EnterpriseMemberService members;
    private final EnterpriseResourceService resources;

    public ReviewedQueryExpansion(@Value("${knowingorder.knowledge.rewrite-enabled:false}") boolean enabled,
            QueryRewriter rewriter, KnowledgeRetriever retriever, ChatModel model,
            EnterpriseMemberService members, EnterpriseResourceService resources) {
        this.enabled = enabled; this.rewriter = rewriter; this.retriever = retriever;
        this.model = model; this.members = members; this.resources = resources;
    }

    public List<Evidence> expand(EnterpriseMemberContext member, long space, String question,
            List<Evidence> original, TaskDeadline parent) {
        if (!enabled) return original;
        // 默认关闭；即使开启，也仍由QueryRewriter核验专用合成空间外发许可。
        FollowUpResolver.active(parent);
        var permission = resources.requireSpace(members.requireCurrent(member), space);
        TaskDeadline stage = parent.limitedTo(Duration.ofSeconds(8));
        QueryRewriter.Outcome outcome = rewriter.rewrite(member, space, question, stage);
        if (outcome.status() != QueryRewriter.Status.CANDIDATE) return original;
        List<Evidence> supplemental = retriever.retrieve(member, space, outcome.alternativeQuery(), stage);
        KnowledgeEvidenceFusion.fuse(original, supplemental);
        var input = Map.of("originalQuestion", question, "alternativeQuery", outcome.alternativeQuery(),
                "candidates", supplemental.stream().map(item -> Map.of("chunkId", item.chunkId().toString(), "content", item.content())).toList());
        KnowledgeEvidenceAdmission.Review review = null;
        try {
            String snapshot = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsBytes(input)));
            var output = model.call("rewrite-review", Map.of("snapshotId", snapshot, "input", input), 1200, stage);
            FollowUpResolver.active(stage);
            fields(output, "snapshotId", "meaning", "items");
            require(output.path("snapshotId").isTextual() && snapshot.equals(output.path("snapshotId").textValue()));
            require(output.path("meaning").isTextual() && output.path("items").isArray()
                    && output.path("items").size() == supplemental.size());
            var meaning = KnowledgeEvidenceAdmission.Meaning.valueOf(output.path("meaning").textValue());
            var items = new ArrayList<KnowledgeEvidenceAdmission.Item>();
            var seen = new java.util.HashSet<String>();
            for (var item : output.path("items")) {
                fields(item, "chunkId", "applicability");
                require(item.path("chunkId").isTextual() && item.path("applicability").isTextual());
                String id = item.path("chunkId").textValue();
                require(seen.add(id));
                Evidence evidence = supplemental.stream().filter(candidate -> candidate.chunkId().toString().equals(id)).findFirst().orElseThrow(ChatModel::invalid);
                items.add(new KnowledgeEvidenceAdmission.Item(evidence,
                        KnowledgeEvidenceAdmission.Applicability.valueOf(item.path("applicability").textValue())));
            }
            review = new KnowledgeEvidenceAdmission.Review(question, outcome.alternativeQuery(), meaning, items);
        } catch (BusinessException | IllegalArgumentException error) {
            // 未通过完整契约或阶段故障时仅保留原检索，不隐式重试或自动接受候选。
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("无法绑定扩展复核快照", error);
        }
        FollowUpResolver.active(parent);
        if (resources.requireSpace(members.requireCurrent(member), space).permissionVersion() != permission.permissionVersion()) throw changed();
        for (Evidence item : java.util.stream.Stream.concat(original.stream(), supplemental.stream()).distinct().toList()) {
            FollowUpResolver.active(parent);
            if (!item.equals(retriever.requireEvidence(member, space, item.chunkId()))) throw changed();
        }
        return KnowledgeEvidenceAdmission.decide(question, outcome.alternativeQuery(), original, supplemental, review)
                .preview().candidates().stream().map(KnowledgeEvidenceFusion.Candidate::evidence).toList();
    }

    private static BusinessException changed() {
        return new BusinessException(com.ragqa.common.ResultCode.CONFLICT, "扩展检索期间权限或资料已变化，请重新查询");
    }

    private static void require(boolean valid) { if (!valid) throw ChatModel.invalid(); }
    private static void fields(com.fasterxml.jackson.databind.JsonNode node, String... names) {
        require(node != null && node.isObject() && node.size() == names.length);
        for (String name : names) require(node.has(name));
    }
}
