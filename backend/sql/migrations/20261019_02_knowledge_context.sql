-- 追问上下文增量。先备份、USE rag_qa，并确认本表不存在；不要重放schema.sql。
-- job_id逻辑关联rag_knowledge_job.id，读取JOIN会话检查企业/成员/账号/空间，不建物理外键。
-- 在任务受理事务中仅插入一次；不随历史状态变化而更新，也不存历史模型答案。
CREATE TABLE rag_knowledge_context (
    job_id CHAR(36) NOT NULL COMMENT '受理任务UUID，逻辑关联任务且每任务唯一',
    context_json TEXT NOT NULL COMMENT '受理时当前及最多4条历史原问题快照，禁止日志输出',
    snapshot_id CHAR(64) NOT NULL COMMENT '完整输入SHA-256，不作权限凭据',
    PRIMARY KEY(job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='私有追问受理快照，不自动更新';
