-- 只读盘点：先在MySQL控制台执行，不修改账号、文档或任务。
-- 仅选择开发库rag_qa；不读取密码散列、凭据、原件路径及问答正文。
USE rag_qa;
SELECT DATABASE() AS database_name, VERSION() AS mysql_version;
SELECT id, name, status, permission_version FROM sys_enterprise ORDER BY id;
SELECT m.enterprise_id, m.id AS member_id, m.user_id, u.username,
       m.role, m.status AS member_status, u.status AS account_status, u.deleted
FROM sys_enterprise_member m JOIN sys_user u ON u.id = m.user_id
ORDER BY m.enterprise_id, m.id;
SELECT id, enterprise_id, name, status FROM sys_department ORDER BY enterprise_id, id;
SELECT s.id, s.enterprise_id, s.name, s.status, s.permission_version,
       COUNT(d.id) AS document_count
FROM rag_knowledge_space s
LEFT JOIN rag_document d ON d.knowledge_space_id = s.id AND d.enterprise_id = s.enterprise_id
GROUP BY s.id, s.enterprise_id, s.name, s.status, s.permission_version
ORDER BY s.enterprise_id, s.id;
SELECT d.id, d.enterprise_id, d.knowledge_space_id, d.title, d.deleted,
       d.published_version_id, d.revision, COUNT(v.id) AS version_count
FROM rag_document d LEFT JOIN rag_document_version v ON v.document_id = d.id
GROUP BY d.id, d.enterprise_id, d.knowledge_space_id, d.title, d.deleted,
         d.published_version_id, d.revision
ORDER BY d.enterprise_id, d.knowledge_space_id, d.id;
SELECT 'rag_conversation' AS table_name, COUNT(*) AS row_count FROM rag_conversation
UNION ALL SELECT 'rag_message', COUNT(*) FROM rag_message
UNION ALL SELECT 'rag_chunk', COUNT(*) FROM rag_chunk
UNION ALL SELECT 'rag_knowledge_chat', COUNT(*) FROM rag_knowledge_chat
UNION ALL SELECT 'rag_knowledge_job', COUNT(*) FROM rag_knowledge_job
UNION ALL SELECT 'rag_knowledge_feedback', COUNT(*) FROM rag_knowledge_feedback;
SELECT enterprise_id, space_id, status, COUNT(*) AS job_count
FROM rag_ingest_job GROUP BY enterprise_id, space_id, status;
SELECT enterprise_id, space_id, status, COUNT(*) AS job_count
FROM rag_vector_job GROUP BY enterprise_id, space_id, status;
SELECT enterprise_id, space_id, COUNT(*) AS pointer_count,
       SUM(job_id IS NOT NULL) AS published_pointer_count
FROM rag_vector_publication GROUP BY enterprise_id, space_id;
