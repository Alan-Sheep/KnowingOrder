package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.domain.dto.KnowledgeAnswers.*;
import com.ragqa.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static com.ragqa.rag.chat.QuestionPlanner.fields;
import static com.ragqa.rag.chat.QuestionPlanner.require;

/** 逐段复核“引文是否支持结论”；不是另一份知识来源，也不能消除同模型共同误判。 */
public final class AnswerSupportReview {
    private final ChatModel model;
    public AnswerSupportReview(ChatModel model) { this.model = model; }

    public List<Part> review(String question, List<Part> parts, TaskDeadline parent) {
        var claims = new ArrayList<Map<String, Object>>();
        for (Part part : parts) {
            for (int i = 0; i < part.paragraphs().size(); i++) {
                Paragraph paragraph = part.paragraphs().get(i);
                claims.add(Map.of("id", part.id() + ":" + i, "text", paragraph.text(), "quotes",
                        paragraph.citations().stream().map(citation -> Map.of("sourceId", citation.sourceId(), "quote", citation.quote())).toList()));
            }
        }
        if (claims.isEmpty()) return parts;
        Map<String, Object> input = Map.of("question", question, "claims", claims);
        String snapshot = snapshot(input);
        try {
            TaskDeadline stage = parent.limitedTo(Duration.ofSeconds(5));
            var output = model.call("knowledge-support", Map.of("snapshotId", snapshot, "input", input), 1200, stage);
            FollowUpResolver.active(stage);
            fields(output, "snapshotId", "verdicts");
            require(output.path("snapshotId").isTextual() && snapshot.equals(output.path("snapshotId").textValue()));
            require(output.path("verdicts").isArray() && output.path("verdicts").size() == claims.size());
            var verdicts = new java.util.HashMap<String, String>();
            for (var item : output.path("verdicts")) {
                fields(item, "id", "verdict");
                require(item.path("id").isTextual() && item.path("verdict").isTextual());
                String id = item.path("id").textValue(), verdict = item.path("verdict").textValue();
                require(claims.stream().anyMatch(claim -> claim.get("id").equals(id))
                        && List.of("SUPPORTED", "UNSUPPORTED", "UNKNOWN").contains(verdict)
                        && verdicts.putIfAbsent(id, verdict) == null);
            }
            var result = new ArrayList<Part>();
            for (Part part : parts) {
                boolean supported = true;
                for (int i = 0; i < part.paragraphs().size(); i++)
                    supported &= "SUPPORTED".equals(verdicts.get(part.id() + ":" + i));
                // 整个子问题降级，避免删去否定或冲突一方后仍展示“已有依据”的结论。
                result.add(supported ? part : fallback(part, "引文未能充分支持本题全部结论；已隐藏生成文字，请核对原文或向负责人反馈。"));
            }
            FollowUpResolver.active(parent);
            return List.copyOf(result);
        } catch (BusinessException error) {
            FollowUpResolver.active(parent);
            return parts.stream().map(part -> part.paragraphs().isEmpty() ? part
                    : fallback(part, "结论依据复核未完成；本次只保留原文，不自动重试。" )).toList();
        }
    }

    private static Part fallback(Part part, String message) {
        return new Part(part.id(), part.question(), "EVIDENCE_ONLY", List.of(), List.of(message));
    }
    private static String snapshot(Object input) {
        try {
            String json = new ObjectMapper().writeValueAsString(input);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("无法绑定结论复核快照", error); }
    }
}
