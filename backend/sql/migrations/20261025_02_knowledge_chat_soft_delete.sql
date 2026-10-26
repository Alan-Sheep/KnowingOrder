-- 会话删除（软删除）增量。执行前先备份 rag_knowledge_chat，确认 USE rag_qa，禁止重放。
-- 为什么不用物理 DELETE：问答任务、上下文快照与反馈都属于审计数据，
-- 物理级联删除会让「回答可回溯」和已有反馈记录同时失效。这里只打时间戳，
-- 列表查询按 deleted_ms IS NULL 过滤，审计链路保持完整。
-- 该列可重复执行判定：存在则跳过，避免重复加列报 1060。
SET @has_column = (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'rag_knowledge_chat'
                     AND column_name = 'deleted_ms');
SET @ddl = IF(@has_column = 0,
    'ALTER TABLE rag_knowledge_chat ADD COLUMN deleted_ms BIGINT DEFAULT NULL COMMENT ''本人删除时刻，NULL 表示仍在列表可见'' AFTER created_ms, ADD KEY idx_chat_live (enterprise_id, member_id, space_id, deleted_ms, created_ms)',
    'SELECT ''deleted_ms 已存在，跳过'' AS note');
PREPARE statement FROM @ddl;
EXECUTE statement;
DEALLOCATE PREPARE statement;
