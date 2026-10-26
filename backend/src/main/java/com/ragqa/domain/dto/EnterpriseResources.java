package com.ragqa.domain.dto;

import java.time.LocalDateTime;

/** 授权后返回的最小只读视图，不包含原件存储路径、密码或其他成员资料。 */
public final class EnterpriseResources {
    private EnterpriseResources() {
    }

    public record Conversation(Long id, String title, LocalDateTime createdAt) {
    }

    public record Message(Long id, Long conversationId, String role, String content,
            Integer tokenCount, LocalDateTime createdAt) {
    }

    public record KnowledgeSpace(Long id, String name, long permissionVersion) {
    }

    public record Document(Long id, Long knowledgeSpaceId, String title, String sourceType,
            int status, int chunkCount) {
    }

    /** 仅任务归属锚点；不虚构执行状态、答案、重连或后台调度能力。 */
    public record TaskReference(Long id, Long conversationId, Long messageId) {
    }
}
