package com.ragqa.domain.dto;

import java.util.List;

/**
 * 一次问答的业务结果。外层 Result 成功仅表示处理完成，实际回答情况看 status。
 * 引用编号包含问题编号，避免不同子问题的来源串号；正文按纯文本交付。
 */
public record ChatAnswer(String requestId, Status status, String message,
        List<Part> answers, long elapsedMillis) {

    public ChatAnswer {
        answers = List.copyOf(answers);
    }

    public enum Status { COMPLETED, PARTIAL, NO_ANSWER }

    public enum PartStatus {
        ANSWERED, PARTIAL, NO_EVIDENCE, INSUFFICIENT_EVIDENCE,
        CLARIFICATION_NEEDED, DEPENDENCY_FAILED, FAILED, TIMED_OUT
    }

    /** GENERAL 明确表示一般建议；WEB 表示经过检索，不能把两者混为一谈。 */
    public enum Basis { GENERAL, WEB }

    public record Part(int id, String question, PartStatus status, Basis basis,
            String answer, String message, List<Source> sources) {
        public Part {
            sources = List.copyOf(sources);
        }
    }

    public record Source(String id, String title, String url, String excerpt) {
    }
}
