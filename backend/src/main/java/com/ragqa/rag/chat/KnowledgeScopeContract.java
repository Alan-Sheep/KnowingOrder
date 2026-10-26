package com.ragqa.rag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 范围识别结果的纯契约：校验完整输入绑定与逐字覆盖，不判断语义、不授权、不调用模型。 */
public final class KnowledgeScopeContract {
    private KnowledgeScopeContract() { }

    public enum Membership { IN_SCOPE, OUT_OF_SCOPE, UNKNOWN }

    /** 必须由服务端提供；定义正文也参与摘要，不能只依赖编号和版本。 */
    public record Profile(String id, long version, String definition) {
        @Override public String toString() { return "Profile[范围正文不记录]"; }
    }

    /** 组织、成员、空间及话题线索必须来自已授权入口；摘要不能替代当前权限复核。 */
    public record Input(String requestId, long enterpriseId, long memberId, long spaceId,
            Profile profile, String question, String antecedentQuestion) {
        @Override public String toString() { return "Input[问题及上下文不记录]"; }
    }

    public record Part(int id, String question, Membership membership) {
        @Override public String toString() { return "Part[id=" + id + ", membership=" + membership + ", 正文不记录]"; }
    }

    public record Proposal(Input input, List<Part> parts) {
        public Proposal { parts = List.copyOf(parts); }
        @Override public String toString() { return "Proposal[parts=" + parts.size() + ", 正文不记录]"; }
    }

    /** 沿用追问契约的带长度前缀UTF-16摘要；只保证输入一致，不是签名或语义正确证明。 */
    public static String snapshotId(Input input) {
        validate(input);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, "knowledge-scope:v1");
            add(digest, input.requestId());
            add(digest, Long.toString(input.enterpriseId()));
            add(digest, Long.toString(input.memberId()));
            add(digest, Long.toString(input.spaceId()));
            add(digest, input.profile().id());
            add(digest, Long.toString(input.profile().version()));
            add(digest, input.profile().definition());
            add(digest, input.question());
            digest.update((byte) (input.antecedentQuestion() == null ? 0 : 1));
            if (input.antecedentQuestion() != null) add(digest, input.antecedentQuestion());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境缺少SHA-256", error);
        }
    }

    /**
     * 上游须使用ChatModel的严格JSON入口拒绝重复键及尾随内容。
     * 非法输出抛异常，不能偷偷修正成OUT_OF_SCOPE或NO_EVIDENCE；合法UNKNOWN原样保留。
     */
    public static Proposal proposal(Input input, JsonNode output) {
        String expected = snapshotId(input);
        fields(output, "snapshotId", "parts");
        require(output.path("snapshotId").isTextual() && expected.equals(output.path("snapshotId").textValue()));
        JsonNode parts = output.path("parts");
        // 沿用现有回答最多3组的边界；超量直接拒绝，不能截断丢掉后半句。
        require(parts.isArray() && !parts.isEmpty() && parts.size() <= 3);
        var result = new ArrayList<Part>();
        int covered = 0;
        for (JsonNode node : parts) {
            fields(node, "question", "scope");
            require(node.path("question").isTextual() && node.path("scope").isTextual());
            String question = node.path("question").textValue();
            require(text(question, 1200) && input.question().startsWith(question, covered));
            Membership membership;
            try { membership = Membership.valueOf(node.path("scope").textValue()); }
            catch (IllegalArgumentException error) { throw invalid(); }
            covered += question.length();
            result.add(new Part(result.size() + 1, question, membership));
        }
        // 不去标点、不strip、不改写；分段必须逐字覆盖原句，避免否定、金额与条件丢失。
        require(covered == input.question().length());
        return new Proposal(input, result);
    }

    private static void validate(Input input) {
        require(input != null && uuid(input.requestId()) && input.enterpriseId() > 0 && input.memberId() > 0
                && input.spaceId() > 0 && input.profile() != null && input.profile().id() != null
                && input.profile().id().matches("[a-z][a-z0-9-]{0,63}") && input.profile().version() > 0
                && text(input.profile().definition(), 2400) && text(input.question(), 1200)
                && (input.antecedentQuestion() == null || text(input.antecedentQuestion(), 1200)));
    }

    private static boolean text(String value, int limit) {
        return value != null && value.length() <= limit
                && value.codePoints().anyMatch(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp))
                && value.codePoints().noneMatch(cp -> (Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t')
                        || Character.getType(cp) == Character.FORMAT || (cp >= 0xD800 && cp <= 0xDFFF));
    }

    private static boolean uuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException error) { return false; }
    }

    private static void fields(JsonNode value, String... names) {
        require(value != null && value.isObject() && value.size() == names.length);
        for (String name : names) require(value.has(name));
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
        return new BusinessException(ResultCode.BIZ_ERROR, "问题范围识别数据校验未通过");
    }

    private static void require(boolean valid) { if (!valid) throw invalid(); }
}
