package com.ragqa.common;

/**
 * 基础业务常量。取值与 schema.sql 中各字段注释保持一致，修改时需同步两处。
 */
public final class Constants {

    private Constants() {
    }

    /** 日期时间格式 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE_PATTERN = "yyyy-MM-dd";
    public static final String TIME_ZONE = "GMT+8";

    /** 逻辑删除标记（deleted 字段） */
    public static final class Deleted {
        public static final int NO = 0;
        public static final int YES = 1;

        private Deleted() {
        }
    }

    /** 用户账号状态（sys_user.status） */
    public static final class UserStatus {
        public static final int NORMAL = 0;
        public static final int DISABLED = 1;

        private UserStatus() {
        }
    }

    /** 文档解析状态（rag_document.status） */
    public static final class DocumentStatus {
        public static final int PENDING = 0;
        public static final int PARSING = 1;
        public static final int DONE = 2;
        public static final int FAILED = 3;

        private DocumentStatus() {
        }
    }

    /** 文档来源类型（rag_document.source_type） */
    public static final class SourceType {
        public static final String UPLOAD = "UPLOAD";
        public static final String LINK = "LINK";

        private SourceType() {
        }
    }

    /** 消息角色（rag_message.role） */
    public static final class MessageRole {
        public static final String USER = "user";
        public static final String ASSISTANT = "assistant";

        private MessageRole() {
        }
    }
}
