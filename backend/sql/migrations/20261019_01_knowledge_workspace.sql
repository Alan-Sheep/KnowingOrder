-- 企业问答持久会话、任务与反馈增量。先备份并确认USE rag_qa，禁止重放既有schema。
-- 不建物理外键；所有关联在服务层和授权SQL中核验。DDL失败需逐表核对，不盲目重跑。
CREATE TABLE rag_knowledge_chat (
    id CHAR(36) NOT NULL COMMENT '客户端生成的会话UUID，重连复用',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '企业逻辑关联',
    member_id BIGINT UNSIGNED NOT NULL COMMENT '归属成员，通讯录可见不等于可读',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '归属账号',
    space_id BIGINT UNSIGNED NOT NULL COMMENT '本会话固定知识空间',
    title VARCHAR(80) NOT NULL COMMENT '首问标题',
    created_ms BIGINT NOT NULL COMMENT '服务端UTC创建毫秒',
    PRIMARY KEY(id),
    KEY idx_chat_owner(enterprise_id,member_id,space_id,created_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业私有问答会话';

CREATE TABLE rag_knowledge_job (
    id CHAR(36) NOT NULL COMMENT '客户端请求UUID，重复提交必须复用',
    chat_id CHAR(36) NOT NULL COMMENT '所属会话逻辑关联',
    auth_version BIGINT NOT NULL COMMENT '受理时账号认证版本，撤销后不升级',
    question VARCHAR(1200) NOT NULL COMMENT '用户原问题，禁止日志输出',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING/RUNNING/COMPLETED/FAILED/CANCELLED/TIMED_OUT',
    created_ms BIGINT NOT NULL COMMENT '首次受理UTC毫秒',
    deadline_ms BIGINT NOT NULL COMMENT '首次受理后30秒硬截止，重连不续期',
    finished_ms BIGINT DEFAULT NULL COMMENT '终态时刻',
    answer_json LONGTEXT COMMENT '完整回答快照，每次读取重新核权',
    error_code INT DEFAULT NULL COMMENT '安全错误码，不存第三方异常正文',
    PRIMARY KEY(id),
    KEY idx_job_chat(chat_id,created_ms,id),
    KEY idx_job_dispatch(status,deadline_ms,created_ms),
    CONSTRAINT chk_job_status CHECK(status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED','TIMED_OUT')),
    CONSTRAINT chk_job_deadline CHECK(deadline_ms > created_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='有界问答任务，不自动重放运行中任务';

CREATE TABLE rag_knowledge_feedback (
    id CHAR(36) NOT NULL COMMENT '反馈请求UUID，重试复用',
    job_id CHAR(36) NOT NULL COMMENT '本人回答任务逻辑关联',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '企业范围',
    space_id BIGINT UNSIGNED NOT NULL COMMENT '知识空间范围',
    member_id BIGINT UNSIGNED NOT NULL COMMENT '反馈人',
    chunk_id BIGINT UNSIGNED DEFAULT NULL COMMENT '选中的引用片段，原版本保存在任务快照',
    category VARCHAR(20) NOT NULL COMMENT 'INCORRECT/MISSING/OUTDATED/OTHER',
    comment_text VARCHAR(1000) NOT NULL COMMENT '员工说明，纯文本展示',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED/DISMISSED',
    resolution VARCHAR(1000) DEFAULT NULL COMMENT '维护者处理说明，不自动改制度',
    resolved_by BIGINT UNSIGNED DEFAULT NULL COMMENT '处理成员',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '并发处理乐观版本',
    created_ms BIGINT NOT NULL COMMENT '提交UTC毫秒',
    PRIMARY KEY(id),
    KEY idx_feedback_space(enterprise_id,space_id,status,created_ms),
    CONSTRAINT chk_feedback_status CHECK(status IN ('OPEN','RESOLVED','DISMISSED')),
    CONSTRAINT chk_feedback_revision CHECK(revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可追溯知识反馈与人工处理';
