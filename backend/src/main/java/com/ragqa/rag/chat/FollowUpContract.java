package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeWorkspaceMapper.ContextQuestion;
import com.ragqa.service.KnowledgeConversationContextService.Context;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.UUID;

/** 追问候选的纯契约；只能核对结构和输入绑定，不能判断话题是否选对，也不授权检索。 */
public final class FollowUpContract {
    private FollowUpContract() { }

    public enum Relation { STANDALONE, FOLLOW_UP, UNRESOLVED }

    public record Proposal(String requestId, String chatId, String question,
            Relation relation, ContextQuestion antecedent) {
        @Override public String toString() {
            return "Proposal[relation=" + relation + ", 正文不记录]";
        }
    }

    /**
     * context必须来自授权读取入口；摘要只绑定完整输入，不是签名、权限或持久化快照。
     * 使用带长度前缀的UTF-16编码，逐字保留Java原问题，避免连接字符串造成边界歧义。
     */
    public static String snapshotId(Context context) {
        validate(context);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, "follow-up:v1");
            add(digest, context.requestId());
            add(digest, context.chatId());
            add(digest, context.question());
            digest.update(ByteBuffer.allocate(4).putInt(context.previous().size()).array());
            for (ContextQuestion item : context.previous()) {
                add(digest, item.id());
                add(digest, item.question());
                digest.update(ByteBuffer.allocate(8).putLong(item.createdMs()).array());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境缺少SHA-256", error);
        }
    }

    /** 调用方须沿用ChatModel的重复键/尾随内容严格JSON解析；本函数不调用模型或重试。 */
    public static Proposal proposal(Context context, JsonNode output) {
        String expected = snapshotId(context);
        require(output != null && output.isObject() && output.size() == 3
                && output.path("snapshotId").isTextual() && output.path("relation").isTextual()
                && output.has("antecedentId") && expected.equals(output.path("snapshotId").textValue()));
        Relation relation;
        try {
            relation = Relation.valueOf(output.path("relation").textValue());
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
        JsonNode id = output.get("antecedentId");
        ContextQuestion antecedent = null;
        if (relation == Relation.FOLLOW_UP) {
            require(id.isTextual());
            antecedent = context.previous().stream().filter(item -> item.id().equals(id.textValue()))
                    .findFirst().orElseThrow(FollowUpContract::invalid);
            long time = antecedent.createdMs();
            // 同毫秒的候选没有可信先后，本单元保守拒绝，不用UUID制造时序。
            require(context.previous().stream().filter(item -> item.createdMs() == time).count() == 1);
        } else {
            require(id.isNull());
        }
        // 原问题不改写、不拼接；即使编号合法，也只是待语义验证的候选关联。
        return new Proposal(context.requestId(), context.chatId(), context.question(), relation, antecedent);
    }

    private static void validate(Context context) {
        require(context != null && uuid(context.requestId()) && uuid(context.chatId())
                && question(context.question()) && context.previous().size() <= 4);
        var ids = new HashSet<String>();
        long previousTime = -1;
        int length = 0;
        for (ContextQuestion item : context.previous()) {
            require(item != null && uuid(item.id()) && !context.requestId().equals(item.id())
                    && ids.add(item.id()) && question(item.question()) && item.createdMs() >= 0
                    && item.createdMs() >= previousTime);
            previousTime = item.createdMs();
            length += item.question().length();
        }
        require(length <= 2400);
        // 当前受理时间不在Context中；历史早于当前的保证来自授权SQL，不能在此伪造验证。
    }

    private static boolean uuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static boolean question(String value) {
        return value != null && value.length() <= 1200
                && value.codePoints().anyMatch(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp));
    }

    private static void add(MessageDigest digest, String value) {
        digest.update(ByteBuffer.allocate(4).putInt(value.length()).array());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            digest.update((byte) (character >>> 8));
            digest.update((byte) character);
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ResultCode.BIZ_ERROR, "追问候选数据校验未通过");
    }

    private static void require(boolean valid) {
        if (!valid) throw invalid();
    }
}
