-- 仅供Replace-SchoolDemoData.ps1以无--force的MySQL批处理执行。
-- 不在交互控制台SOURCE本文件：任一错误须终止客户端并断开，使未提交事务回滚。
-- 固定为2026-10-24用户盘点的rag_qa/企业1，不清库、不改表、不重置自增值。
USE rag_qa;
SET SESSION innodb_lock_wait_timeout = 5;
CREATE TEMPORARY TABLE knowingorder_school_guard (
    item VARCHAR(80) PRIMARY KEY,
    passed TINYINT NOT NULL,
    CONSTRAINT chk_school_replacement_guard CHECK (passed = 1)
);
START TRANSACTION;
SELECT id AS locked_enterprise FROM sys_enterprise WHERE id = 1 FOR UPDATE;
INSERT INTO knowingorder_school_guard VALUES ('enterprise',
    IF((SELECT COUNT(*) FROM sys_enterprise) = 1 AND EXISTS (
        SELECT 1 FROM sys_enterprise WHERE id = 1 AND name = '知序' AND status = 0 AND permission_version = 3), 1, 0));
INSERT INTO knowingorder_school_guard VALUES ('admin',
    IF((SELECT COUNT(*) FROM sys_user) = 4 AND EXISTS (
        SELECT 1 FROM sys_user u JOIN sys_enterprise_member m ON m.user_id = u.id
        WHERE u.id = 25 AND BINARY u.username = BINARY 'adminW' AND u.status = 0 AND u.deleted = 0
          AND m.id = 1 AND m.enterprise_id = 1 AND m.role = 'ADMIN' AND m.status = 0 AND m.department_id IS NULL), 1, 0));
SET @school_admin_password_hash = (SELECT password_hash FROM sys_user WHERE id = 25);
INSERT INTO knowingorder_school_guard VALUES ('members',
    IF((SELECT COUNT(*) FROM sys_enterprise_member) = 4 AND
       (SELECT COUNT(*) FROM sys_enterprise_member m JOIN sys_user u ON u.id = m.user_id
        WHERE m.enterprise_id = 1 AND m.role = 'EMPLOYEE' AND m.status = 1 AND (
          (m.id = 12 AND u.id = 36 AND BINARY u.username = BINARY 'org-test-1792391328887') OR
          (m.id = 33 AND u.id = 57 AND BINARY u.username = BINARY 'office-live-1792830843016-cb1ac17a') OR
          (m.id = 34 AND u.id = 58 AND BINARY u.username = BINARY 'office-live-1792831327440-5b80b073'))) = 3, 1, 0));
INSERT INTO knowingorder_school_guard VALUES ('department',
    IF((SELECT COUNT(*) FROM sys_department) = 1 AND EXISTS (
        SELECT 1 FROM sys_department WHERE id = 1 AND enterprise_id = 1 AND status = 1
          AND name = 'org-test-1792391328887-部门-归档'), 1, 0));
INSERT INTO knowingorder_school_guard VALUES ('spaces',
    IF((SELECT COUNT(*) FROM rag_knowledge_space) = 9 AND
       (SELECT COUNT(*) FROM rag_knowledge_space WHERE enterprise_id = 1 AND status = 0
        AND id IN (5,12,13,14,15,16,17,38,39)
        AND (name LIKE '联调测试空间-%' OR name IN (
          'office-live-1792830843016-cb1ac17a','office-live-1792831327440-5b80b073'))) = 9, 1, 0));
INSERT INTO knowingorder_school_guard VALUES ('documents',
    IF((SELECT COUNT(*) FROM rag_document) = 18 AND
       (SELECT COUNT(*) FROM rag_document WHERE enterprise_id = 1 AND deleted = 0
        AND id IN (8,9,10,11,12,13,14,15,16,37,38,39,40,41,42,43,44,45)
        AND knowledge_space_id IN (5,12,13,14,15,16,17,38,39)) = 18 AND
       (SELECT COUNT(*) FROM rag_document WHERE published_version_id IS NOT NULL) = 1 AND EXISTS (
         SELECT 1 FROM rag_document WHERE id = 15 AND published_version_id = 14), 1, 0));
INSERT INTO knowingorder_school_guard VALUES ('versions-and-chunks',
    IF((SELECT COUNT(*) FROM rag_document_version) = 18 AND
       NOT EXISTS (SELECT 1 FROM rag_document_version v LEFT JOIN rag_document d ON d.id = v.document_id
         WHERE d.id IS NULL OR v.enterprise_id <> d.enterprise_id OR v.knowledge_space_id <> d.knowledge_space_id) AND
       (SELECT COUNT(*) FROM rag_chunk) = 44 AND NOT EXISTS (
         SELECT 1 FROM rag_chunk c LEFT JOIN rag_document d ON d.id = c.document_id
         WHERE d.id IS NULL OR d.enterprise_id <> 1), 1, 0));
INSERT INTO knowingorder_school_guard VALUES ('history',
    IF((SELECT COUNT(*) FROM rag_conversation) = 0 AND (SELECT COUNT(*) FROM rag_message) = 0 AND
       (SELECT COUNT(*) FROM rag_task_reference) = 0 AND
       (SELECT COUNT(*) FROM rag_knowledge_chat) = 3 AND
       (SELECT COUNT(*) FROM rag_knowledge_chat WHERE enterprise_id = 1 AND space_id IN (5,12,13,14,15,16,17,38,39)) = 3 AND
       (SELECT COUNT(*) FROM rag_knowledge_job) = 4 AND NOT EXISTS (
         SELECT 1 FROM rag_knowledge_job j LEFT JOIN rag_knowledge_chat c ON c.id = j.chat_id
         WHERE c.id IS NULL OR j.status IN ('PENDING','RUNNING')) AND
       (SELECT COUNT(*) FROM rag_knowledge_feedback) = 1 AND NOT EXISTS (
         SELECT 1 FROM rag_knowledge_feedback WHERE enterprise_id <> 1 OR space_id NOT IN (5,12,13,14,15,16,17,38,39)) AND
       NOT EXISTS (SELECT 1 FROM rag_knowledge_context c LEFT JOIN rag_knowledge_job j ON j.id = c.job_id WHERE j.id IS NULL), 1, 0));
INSERT INTO knowingorder_school_guard VALUES ('ingest-and-vector',
    IF((SELECT COUNT(*) FROM rag_ingest_job) = 11 AND NOT EXISTS (
         SELECT 1 FROM rag_ingest_job WHERE enterprise_id <> 1 OR space_id NOT IN (5,12,13,14,15,16,17,38,39)
           OR status IN ('QUEUED','RUNNING')) AND
       (SELECT COUNT(*) FROM rag_vector_job) = 0 AND
       (SELECT COUNT(*) FROM rag_vector_manifest_entry) = 0 AND
       (SELECT COUNT(*) FROM rag_vector_publication) = 0, 1, 0));
-- 访客身份冻结保留；没有访客会话时，其凭据摘要不进入日常工作区，也不作为校园资料迁移目标。
DELETE c FROM rag_knowledge_context c JOIN rag_knowledge_job j ON j.id = c.job_id
JOIN rag_knowledge_chat h ON h.id = j.chat_id WHERE h.enterprise_id = 1 AND h.space_id IN (5,12,13,14,15,16,17,38,39);
DELETE FROM rag_knowledge_feedback WHERE enterprise_id = 1 AND space_id IN (5,12,13,14,15,16,17,38,39);
DELETE j FROM rag_knowledge_job j JOIN rag_knowledge_chat c ON c.id = j.chat_id
WHERE c.enterprise_id = 1 AND c.space_id IN (5,12,13,14,15,16,17,38,39);
DELETE FROM rag_knowledge_chat WHERE enterprise_id = 1 AND space_id IN (5,12,13,14,15,16,17,38,39);
DELETE FROM rag_ingest_job WHERE enterprise_id = 1 AND space_id IN (5,12,13,14,15,16,17,38,39);
DELETE c FROM rag_chunk c JOIN rag_document d ON d.id = c.document_id
WHERE d.enterprise_id = 1 AND d.id IN (8,9,10,11,12,13,14,15,16,37,38,39,40,41,42,43,44,45);
DELETE FROM rag_document_version WHERE enterprise_id = 1 AND document_id IN (8,9,10,11,12,13,14,15,16,37,38,39,40,41,42,43,44,45);
DELETE FROM rag_document WHERE enterprise_id = 1 AND id IN (8,9,10,11,12,13,14,15,16,37,38,39,40,41,42,43,44,45);
DELETE FROM rag_knowledge_grant WHERE enterprise_id = 1 AND knowledge_space_id IN (5,12,13,14,15,16,17,38,39);
DELETE FROM rag_knowledge_space WHERE enterprise_id = 1 AND id IN (5,12,13,14,15,16,17,38,39);
DELETE FROM sys_enterprise_member WHERE enterprise_id = 1 AND id IN (12,33,34) AND role = 'EMPLOYEE' AND status = 1;
DELETE FROM sys_user WHERE id IN (36,57,58) AND NOT EXISTS (SELECT 1 FROM sys_enterprise_member m WHERE m.user_id = sys_user.id);
DELETE FROM sys_department WHERE enterprise_id = 1 AND id = 1 AND status = 1;
UPDATE sys_enterprise SET name = '知序大学', permission_version = permission_version + 1
WHERE id = 1 AND status = 0 AND permission_version = 3;
INSERT INTO knowingorder_school_guard VALUES ('final-admin-and-empty-business',
    IF((SELECT COUNT(*) FROM sys_user) = 1 AND EXISTS (
         SELECT 1 FROM sys_user WHERE id = 25 AND BINARY username = BINARY 'adminW'
           AND BINARY password_hash = BINARY @school_admin_password_hash AND status = 0 AND deleted = 0) AND
       (SELECT COUNT(*) FROM sys_enterprise_member) = 1 AND
       (SELECT COUNT(*) FROM rag_document) = 0 AND (SELECT COUNT(*) FROM rag_document_version) = 0 AND
       (SELECT COUNT(*) FROM rag_chunk) = 0 AND (SELECT COUNT(*) FROM rag_knowledge_space) = 0 AND
       (SELECT COUNT(*) FROM rag_knowledge_job) = 0 AND (SELECT COUNT(*) FROM rag_knowledge_context) = 0 AND
       (SELECT COUNT(*) FROM rag_knowledge_feedback) = 0 AND (SELECT COUNT(*) FROM rag_ingest_job) = 0 AND
       EXISTS (SELECT 1 FROM sys_enterprise WHERE id = 1 AND name = '知序大学' AND permission_version = 4), 1, 0));
COMMIT;
SET @school_admin_password_hash = NULL;
SELECT 'SCHOOL_REPLACEMENT_COMMITTED' AS result, 1 AS enterprise_id, '知序大学' AS school_name,
       25 AS preserved_user_id, 1 AS preserved_member_id;
