package com.ragqa.domain.dto;

import lombok.Data;

/** 版本与片段视图不包含原件存储键或摘要，Long编号沿用字符串输出。 */
public final class KnowledgeDocuments {
    private KnowledgeDocuments() { }

    // 新增姓名放在末尾，保证与 Mapper 查询列顺序一致（MyBatis 按位置映射构造参数）。
    public record Document(Long id, Long knowledgeSpaceId, String title, Long ownerMemberId,
            Long publishedVersionId, long revision, String ownerMemberName) { }

    public record Version(Long id, Long documentId, int versionNo, String originalName, long fileSize,
            String parseStatus, String publicationStatus, int chunkCount, String failureCode,
            Long validFromMs, Long validUntilMs) { }

    public record Chunk(Long id, int chunkIndex, String content, int startOffset, int endOffset,
            int startLine, int endLine) { }

    /** 仅在服务/Mapper间使用，控制器必须转换为公开视图，禁止直接序列化。 */
    @Data
    public static class StoredVersion {
        private Long id;
        private Long documentId;
        private Integer versionNo;
        private String uploadRequestId;
        private String originalName;
        private String storageKey;
        private String contentSha256;
        private String requestSha256;
        private Long fileSize;
        private String parseStatus;
        private String publicationStatus;
        private Integer chunkCount;
        private String failureCode;
        private Long validFromMs;
        private Long validUntilMs;
        private Long createdByMember;

        public Version view() {
            return new Version(id, documentId, versionNo, originalName, fileSize, parseStatus, publicationStatus,
                    chunkCount, failureCode, validFromMs, validUntilMs);
        }
    }
}
