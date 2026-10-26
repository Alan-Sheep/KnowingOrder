package com.ragqa.domain.dto;

import java.util.List;

/** 企业回答只携带已授权的公开业务字段，不暴露原件路径、摘要或凭据。 */
public final class KnowledgeAnswers {
    private KnowledgeAnswers() { }

    public record Evidence(Long chunkId, Long documentId, Long versionId, int versionNo, String title,
            int chunkIndex, String content, int startOffset, int endOffset, int startLine, int endLine) { }

    public record Citation(int sourceId, Evidence evidence, String quote) { }

    public record Paragraph(String text, List<Citation> citations) { }

    public record Part(int id, String question, String status, List<Paragraph> paragraphs, List<String> gaps) { }

    /** mode明确区分本地摘录和虚构资料模型验收；两者均不代表向量召回。 */
    public record Answer(String mode, String status, String message, List<Part> parts,
            List<Evidence> evidence, long elapsedMs) { }
}
