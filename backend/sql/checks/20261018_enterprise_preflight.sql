-- 企业身份迁移前后只读核对；先在IDEA数据库控制台选择目标库，再逐段执行。
-- 仅查询版本与information_schema，不读取业务记录、不修改结构、不执行迁移。
-- 适用MySQL 8.0.16及以上；先核对第1段，版本或目标库不符时停止。
-- 缺项可能是尚未迁移、执行中断或元数据不可见，不得据此自动重放任何脚本。

-- 1. 必须人工确认产品、版本、当前库；无目标库时停止，不在脚本中USE切库。
SELECT VERSION() AS server_version, @@version_comment AS server_product,
       DATABASE() AS selected_database;

-- 2. 所需表及存储设置；表存在不能单独证明结构兼容。
WITH expected(stage, table_name) AS (
    SELECT '既有基础', 'sys_user' UNION ALL
    SELECT '既有基础', 'rag_document' UNION ALL
    SELECT '既有基础', 'rag_chunk' UNION ALL
    SELECT '既有基础', 'rag_conversation' UNION ALL
    SELECT '既有基础', 'rag_message' UNION ALL
    SELECT '01/02前置', 'rag_guest_identity' UNION ALL
    SELECT '03企业', 'sys_enterprise' UNION ALL
    SELECT '03企业', 'sys_department' UNION ALL
    SELECT '03企业', 'sys_enterprise_member' UNION ALL
    SELECT '03企业', 'rag_knowledge_space' UNION ALL
    SELECT '03企业', 'rag_knowledge_grant' UNION ALL
    SELECT '03企业', 'rag_task_reference'
)
SELECT e.stage, e.table_name, t.table_type, t.engine, t.table_collation,
       CASE WHEN t.table_name IS NULL THEN '缺失或不可见'
            WHEN t.table_type <> 'BASE TABLE' OR t.engine <> 'InnoDB'
                OR t.table_collation <> 'utf8mb4_unicode_ci' THEN '设置不符，人工核对'
            ELSE '表存在，继续核对定义' END AS finding
FROM expected e LEFT JOIN information_schema.tables t
    ON t.table_schema = DATABASE() AND t.table_name = e.table_name
ORDER BY e.stage, e.table_name;

-- 3. 迁移涉及的列；展示实际类型、空值、默认值和额外属性，逐项与迁移源码比对。
-- 刻意不将“同名列存在”标作“定义正确”，避免掩盖类型或中间回填状态差异。
WITH expected(stage, table_name, column_name) AS (
    SELECT '01/02前置', 'rag_conversation', 'user_id' UNION ALL
    SELECT '01/02前置', 'rag_conversation', 'guest_id' UNION ALL
    SELECT '01/02前置', 'rag_guest_identity', 'id' UNION ALL
    SELECT '01/02前置', 'rag_guest_identity', 'credential_hash' UNION ALL
    SELECT '01/02前置', 'rag_guest_identity', 'issued_at_epoch_ms' UNION ALL
    SELECT '01/02前置', 'rag_guest_identity', 'accept_until_epoch_ms' UNION ALL
    SELECT '01/02前置', 'rag_guest_identity', 'drain_until_epoch_ms' UNION ALL
    SELECT '01/02前置', 'rag_guest_identity', 'expires_at_epoch_ms' UNION ALL
    SELECT '01/02前置', 'rag_guest_identity', 'status' UNION ALL
    SELECT '01/02前置', 'rag_guest_identity', 'result_read_window_ms' UNION ALL
    SELECT '03企业', 'sys_user', 'auth_version' UNION ALL
    SELECT '03企业', 'rag_conversation', 'enterprise_id' UNION ALL
    SELECT '03企业', 'rag_conversation', 'member_id' UNION ALL
    SELECT '03企业', 'rag_document', 'enterprise_id' UNION ALL
    SELECT '03企业', 'rag_document', 'knowledge_space_id' UNION ALL
    SELECT '03企业', 'sys_enterprise', 'id' UNION ALL
    SELECT '03企业', 'sys_enterprise', 'name' UNION ALL
    SELECT '03企业', 'sys_enterprise', 'status' UNION ALL
    SELECT '03企业', 'sys_enterprise', 'permission_version' UNION ALL
    SELECT '03企业', 'sys_department', 'id' UNION ALL
    SELECT '03企业', 'sys_department', 'enterprise_id' UNION ALL
    SELECT '03企业', 'sys_department', 'name' UNION ALL
    SELECT '03企业', 'sys_department', 'status' UNION ALL
    SELECT '03企业', 'sys_enterprise_member', 'id' UNION ALL
    SELECT '03企业', 'sys_enterprise_member', 'enterprise_id' UNION ALL
    SELECT '03企业', 'sys_enterprise_member', 'user_id' UNION ALL
    SELECT '03企业', 'sys_enterprise_member', 'department_id' UNION ALL
    SELECT '03企业', 'sys_enterprise_member', 'role' UNION ALL
    SELECT '03企业', 'sys_enterprise_member', 'status' UNION ALL
    SELECT '03企业', 'sys_enterprise_member', 'permission_version' UNION ALL
    SELECT '03企业', 'rag_knowledge_space', 'id' UNION ALL
    SELECT '03企业', 'rag_knowledge_space', 'enterprise_id' UNION ALL
    SELECT '03企业', 'rag_knowledge_space', 'name' UNION ALL
    SELECT '03企业', 'rag_knowledge_space', 'status' UNION ALL
    SELECT '03企业', 'rag_knowledge_space', 'permission_version' UNION ALL
    SELECT '03企业', 'rag_knowledge_grant', 'id' UNION ALL
    SELECT '03企业', 'rag_knowledge_grant', 'enterprise_id' UNION ALL
    SELECT '03企业', 'rag_knowledge_grant', 'knowledge_space_id' UNION ALL
    SELECT '03企业', 'rag_knowledge_grant', 'member_id' UNION ALL
    SELECT '03企业', 'rag_knowledge_grant', 'department_id' UNION ALL
    SELECT '03企业', 'rag_knowledge_grant', 'permission' UNION ALL
    SELECT '03企业', 'rag_knowledge_grant', 'status' UNION ALL
    SELECT '03企业', 'rag_task_reference', 'id' UNION ALL
    SELECT '03企业', 'rag_task_reference', 'enterprise_id' UNION ALL
    SELECT '03企业', 'rag_task_reference', 'conversation_id' UNION ALL
    SELECT '03企业', 'rag_task_reference', 'message_id'
), enterprise_tables(table_name) AS (
    SELECT 'sys_enterprise' UNION ALL SELECT 'sys_department' UNION ALL
    SELECT 'sys_enterprise_member' UNION ALL SELECT 'rag_knowledge_space' UNION ALL
    SELECT 'rag_knowledge_grant' UNION ALL SELECT 'rag_task_reference'
), timestamps(column_name) AS (
    SELECT 'created_at' UNION ALL SELECT 'updated_at'
), required_columns AS (
    SELECT * FROM expected UNION ALL
    SELECT '03企业', t.table_name, d.column_name FROM enterprise_tables t CROSS JOIN timestamps d
)
SELECT e.stage, e.table_name, e.column_name, c.column_type, c.is_nullable,
       c.column_default, c.extra, c.collation_name,
       CASE WHEN c.column_name IS NULL THEN '缺失或不可见'
            ELSE '列存在，须比对定义' END AS finding
FROM required_columns e LEFT JOIN information_schema.columns c
    ON c.table_schema = DATABASE() AND c.table_name = e.table_name AND c.column_name = e.column_name
ORDER BY e.stage, e.table_name, e.column_name;

-- 4. 索引检查列顺序、唯一性、前缀及可见性；预期定义来自01/03和空库脚本。
WITH expected(table_name, index_name, expected_columns, expected_non_unique) AS (
    SELECT 'sys_user', 'uk_username', 'username', 0 UNION ALL
    SELECT 'rag_conversation', 'idx_guest_id', 'guest_id', 1 UNION ALL
    SELECT 'rag_guest_identity', 'uk_guest_credential_hash', 'credential_hash', 0 UNION ALL
    SELECT 'rag_guest_identity', 'idx_guest_expiry', 'expires_at_epoch_ms', 1 UNION ALL
    SELECT 'rag_conversation', 'idx_conversation_scope', 'enterprise_id,member_id,user_id,id', 1 UNION ALL
    SELECT 'rag_document', 'idx_document_space', 'enterprise_id,knowledge_space_id,id', 1 UNION ALL
    SELECT 'sys_enterprise', 'PRIMARY', 'id', 0 UNION ALL
    SELECT 'sys_department', 'PRIMARY', 'id', 0 UNION ALL
    SELECT 'sys_department', 'idx_department_enterprise', 'enterprise_id,id', 1 UNION ALL
    SELECT 'sys_enterprise_member', 'PRIMARY', 'id', 0 UNION ALL
    SELECT 'sys_enterprise_member', 'uk_enterprise_user', 'enterprise_id,user_id', 0 UNION ALL
    SELECT 'sys_enterprise_member', 'idx_member_user', 'user_id,enterprise_id', 1 UNION ALL
    SELECT 'rag_knowledge_space', 'PRIMARY', 'id', 0 UNION ALL
    SELECT 'rag_knowledge_space', 'idx_space_enterprise', 'enterprise_id,id', 1 UNION ALL
    SELECT 'rag_knowledge_grant', 'PRIMARY', 'id', 0 UNION ALL
    SELECT 'rag_knowledge_grant', 'uk_grant_member', 'enterprise_id,knowledge_space_id,member_id', 0 UNION ALL
    SELECT 'rag_knowledge_grant', 'uk_grant_department', 'enterprise_id,knowledge_space_id,department_id', 0 UNION ALL
    SELECT 'rag_task_reference', 'PRIMARY', 'id', 0 UNION ALL
    SELECT 'rag_task_reference', 'idx_task_conversation', 'enterprise_id,conversation_id,id', 1
), actual AS (
    SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS actual_columns,
           MIN(non_unique) AS non_unique, MAX(sub_part IS NOT NULL) AS has_prefix,
           MIN(is_visible) AS is_visible, MIN(index_type) AS index_type,
           MAX(collation = 'D') AS has_descending, MAX(column_name IS NULL) AS has_expression
    FROM information_schema.statistics WHERE table_schema = DATABASE()
    GROUP BY table_name, index_name
)
SELECT e.*, a.actual_columns, a.non_unique, a.has_prefix, a.is_visible, a.index_type,
       a.has_descending, a.has_expression,
       CASE WHEN a.index_name IS NULL THEN '缺失或不可见'
            WHEN a.actual_columns IS NULL OR a.actual_columns <> e.expected_columns
                OR a.has_expression <> 0 OR a.non_unique <> e.expected_non_unique
                OR a.has_prefix <> 0 OR a.is_visible <> 'YES' OR a.index_type <> 'BTREE'
                OR a.has_descending <> 0 THEN '定义不符，人工核对'
            ELSE '索引定义匹配' END AS finding
FROM expected e LEFT JOIN actual a ON a.table_name = e.table_name AND a.index_name = e.index_name
ORDER BY e.table_name, e.index_name;

-- 5. 必须同时核对约束是否强制执行及表达式内容；同名并不等于同一约束。
WITH expected(table_name, constraint_name) AS (
    SELECT 'rag_conversation', 'chk_conversation_owner' UNION ALL
    SELECT 'rag_guest_identity', 'chk_guest_status' UNION ALL
    SELECT 'rag_guest_identity', 'chk_guest_read_window' UNION ALL
    SELECT 'rag_guest_identity', 'chk_guest_time_bounds' UNION ALL
    SELECT 'sys_user', 'chk_user_auth_version' UNION ALL
    SELECT 'rag_conversation', 'chk_conversation_enterprise' UNION ALL
    SELECT 'rag_document', 'chk_document_enterprise' UNION ALL
    SELECT 'sys_enterprise', 'chk_enterprise_status' UNION ALL
    SELECT 'sys_enterprise', 'chk_enterprise_version' UNION ALL
    SELECT 'sys_department', 'chk_department_status' UNION ALL
    SELECT 'sys_enterprise_member', 'chk_member_status' UNION ALL
    SELECT 'sys_enterprise_member', 'chk_member_role' UNION ALL
    SELECT 'sys_enterprise_member', 'chk_member_version' UNION ALL
    SELECT 'rag_knowledge_space', 'chk_space_status' UNION ALL
    SELECT 'rag_knowledge_space', 'chk_space_version' UNION ALL
    SELECT 'rag_knowledge_grant', 'chk_grant_target' UNION ALL
    SELECT 'rag_knowledge_grant', 'chk_grant_permission' UNION ALL
    SELECT 'rag_knowledge_grant', 'chk_grant_status'
)
SELECT e.table_name, e.constraint_name, t.enforced, c.check_clause,
       CASE WHEN t.constraint_name IS NULL THEN '缺失或不可见'
            WHEN t.enforced <> 'YES' THEN '未强制执行'
            ELSE '已强制执行，须比对表达式' END AS finding
FROM expected e LEFT JOIN information_schema.table_constraints t
    ON t.constraint_schema = DATABASE() AND t.table_name = e.table_name
        AND t.constraint_name = e.constraint_name AND t.constraint_type = 'CHECK'
LEFT JOIN information_schema.check_constraints c
    ON c.constraint_schema = t.constraint_schema AND c.constraint_name = t.constraint_name
ORDER BY e.table_name, e.constraint_name;

-- 6. 当前约定不建物理外键；如有结果，先核对来源，不由本脚本删除。
SELECT table_name, constraint_name
FROM information_schema.table_constraints
WHERE constraint_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'
    AND table_name IN ('sys_user', 'rag_conversation', 'rag_document', 'rag_guest_identity',
        'sys_enterprise', 'sys_department', 'sys_enterprise_member', 'rag_knowledge_space',
        'rag_knowledge_grant', 'rag_task_reference')
ORDER BY table_name, constraint_name;
