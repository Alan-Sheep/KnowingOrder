package com.ragqa.rag.chat;

import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeWorkspaceMapper.ContextQuestion;
import com.ragqa.service.KnowledgeConversationContextService.Context;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/** 两次有界调用提出并复核话题关联；复核仍可能出错，不是语义真实性证明。 */
@Component
public class FollowUpResolver {
    private final ChatModel model;
    public FollowUpResolver(ChatModel model) { this.model = model; }

    public enum Status { DISABLED, NO_SNAPSHOT, NOT_PERMITTED, STANDALONE, FOLLOW_UP, UNRESOLVED, UNAVAILABLE }
    public record Outcome(Status status, ContextQuestion antecedent, String message) {
        @Override public String toString() { return "Outcome[status=" + status + ", 正文不记录]"; }
    }

    /** 只接受服务端已授权的上下文；调用方负责模型前后核权与外发许可。 */
    public Outcome resolve(Context context, TaskDeadline parent) {
        String snapshot = FollowUpContract.snapshotId(context);
        active(parent);
        if (context.previous().isEmpty()) return fallback(Status.STANDALONE);
        // 两次调用合计最多5秒；不是每次各续5秒，也不重试。
        TaskDeadline stage = parent.limitedTo(Duration.ofSeconds(5));
        Outcome result;
        try {
            var payload = Map.of("snapshotId", snapshot, "question", context.question(), "previous", context.previous());
            var proposal = FollowUpContract.proposal(context, model.call("follow-up", payload, 500, stage));
            active(stage);
            if (proposal.relation() != FollowUpContract.Relation.FOLLOW_UP) {
                result = fallback(proposal.relation() == FollowUpContract.Relation.STANDALONE
                        ? Status.STANDALONE : Status.UNRESOLVED);
            } else {
                // 不把首轮模型解释交给复核，只交完整原问题与所选编号，减少自我辩护。
                var review = model.call("follow-up-review", Map.of("snapshotId", snapshot,
                        "question", context.question(), "previous", context.previous(),
                        "antecedentId", proposal.antecedent().id()), 500, stage);
                active(stage);
                if (review == null || !review.isObject() || review.size() != 3
                        || !review.path("snapshotId").isTextual() || !snapshot.equals(review.path("snapshotId").textValue())
                        || !review.path("antecedentId").isTextual()
                        || !proposal.antecedent().id().equals(review.path("antecedentId").textValue())
                        || !review.path("verdict").isTextual()) throw ChatModel.invalid();
                result = switch (review.path("verdict").textValue()) {
                    case "ACCEPT" -> new Outcome(Status.FOLLOW_UP, proposal.antecedent(),
                            "已按同会话历史问题关联话题；关联由模型识别，请结合引用核对。历史问题不作为制度依据。");
                    case "REJECT", "UNKNOWN" -> fallback(Status.UNRESOLVED);
                    default -> throw ChatModel.invalid();
                };
            }
        } catch (BusinessException unavailable) {
            result = fallback(Status.UNAVAILABLE);
        }
        // 总截止和取消不能吞成增强降级；阶段超时但父任务仍有余量才可继续原问题。
        active(parent);
        return result;
    }

    public static Outcome fallback(Status status) {
        String message = switch (status) {
            case DISABLED -> "上下文关联未启用，本次仅按当前问题处理。";
            case NO_SNAPSHOT -> "此任务未保存受理时上下文，本次不补读后来的历史，仅按当前问题处理。";
            case NOT_PERMITTED -> "此空间未获上下文外发许可，本次仅按当前问题检索。";
            case STANDALONE -> "本次按独立问题处理，未继承历史话题。";
            case UNRESOLVED -> "未能可靠确定历史话题，本次仅处理当前原问题；指代不清的内容可能缺少依据。";
            default -> "上下文识别暂不可用，本次仅处理当前原问题，未自动重试。";
        };
        return new Outcome(status, null, message);
    }

    public static void active(TaskDeadline deadline) {
        if (Thread.currentThread().isInterrupted()) throw new BusinessException(ResultCode.SERVICE_BUSY, "任务已停止等待");
        deadline.timeoutFor(Duration.ofSeconds(1));
    }
}
