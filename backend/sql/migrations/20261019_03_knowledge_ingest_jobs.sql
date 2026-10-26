-- 文档入库任务增量：先备份并确认USE rag_qa；仅两表均不存在时执行。
-- 不重放schema.sql；部分成功时先核对SHOW CREATE TABLE，不盲目重跑。
-- 不建物理外键；原件、企业和版本的关联由服务端授权及事务核验。
CREATE TABLE rag_ingest_gate (
    id INT NOT NULL COMMENT '唯一受理锁行，不保存业务内容',
    PRIMARY KEY(id),
    CONSTRAINT chk_ingest_gate CHECK(id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='入库队列容量的跨节点受理锁';

INSERT INTO rag_ingest_gate(id) VALUES(1);

CREATE TABLE rag_ingest_job (
    id CHAR(36) NOT NULL COMMENT '受理UUID，重传保持相同编号',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '企业逻辑关联',
    space_id BIGINT UNSIGNED NOT NULL COMMENT '知识空间逻辑关联',
    document_id BIGINT UNSIGNED NOT NULL COMMENT '逻辑文档编号',
    version_id BIGINT UNSIGNED NOT NULL COMMENT '原件版本编号',
    member_id BIGINT UNSIGNED NOT NULL COMMENT '提交成员，读取任务仍须维护权限',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '账号编号',
    auth_version BIGINT NOT NULL COMMENT '受理认证版本，后台复核',
    expected_revision BIGINT NOT NULL COMMENT '文档预期修订，不自动覆盖并发修改',
    action VARCHAR(16) NOT NULL COMMENT 'PARSE幂等解析，REBUILD仅草稿重建',
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/TIMED_OUT',
    attempts INT NOT NULL DEFAULT 0 COMMENT '执行次数，最多两次，仅租约失效可自动恢复',
    lease_token CHAR(36) DEFAULT NULL COMMENT '执行栅栏令牌，不返回前端',
    lease_until_ms BIGINT DEFAULT NULL COMMENT '15秒租约，不延长原总截止',
    created_ms BIGINT NOT NULL COMMENT '受理UTC毫秒',
    deadline_ms BIGINT NOT NULL COMMENT '受理起30秒总截止',
    finished_ms BIGINT DEFAULT NULL COMMENT '终态UTC毫秒',
    failure_code VARCHAR(32) DEFAULT NULL COMMENT '白名单安全分类，不保存异常正文',
    PRIMARY KEY(id),
    KEY idx_ingest_owner(enterprise_id,space_id,member_id,created_ms,id),
    KEY idx_ingest_version(document_id,version_id,status),
    KEY idx_ingest_queue(status,created_ms),
    KEY idx_ingest_deadline(status,deadline_ms),
    CONSTRAINT chk_ingest_action CHECK(action IN ('PARSE','REBUILD')),
    CONSTRAINT chk_ingest_status CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')),
    CONSTRAINT chk_ingest_attempts CHECK(attempts BETWEEN 0 AND 2),
    CONSTRAINT chk_ingest_revision CHECK(expected_revision>0 AND auth_version>0),
    CONSTRAINT chk_ingest_time CHECK(created_ms>=0 AND deadline_ms>created_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='私有文字入库持久任务，片段与成功终态同事务';
