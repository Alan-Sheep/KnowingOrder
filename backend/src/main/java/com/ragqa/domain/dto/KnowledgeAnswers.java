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

    /** 跨空间回答的证据标注来源空间；spaceName仅用于展示，授权判断仍以spaceId服务端校验为准。 */
    public record SpaceEvidence(Long spaceId, String spaceName, Evidence evidence) { }

    /** 企业级跨空间回答：一次问题合并全部授权空间的证据；parts与引用规则同单空间Answer。 */
    public record CrossAnswer(String mode, String status, String message, List<Part> parts,
            List<SpaceEvidence> evidence, long elapsedMs) { }
}
