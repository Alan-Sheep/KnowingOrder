-- 云端演示库学校化：把「知序科技有限公司 / 5 个企业部门」改成「知序大学 / 6 个学校部门」。
-- 背景：finish.sh 建的企业名与企业式部门来自通用种子；本脚本只改身份与命名，不动账号、授权、业务单据。
-- 幂等：全部条件化 UPDATE/INSERT，重复执行空跑；不删数据、不重建表、不重置自增。
-- 适用范围：仅演示库（云端）。本地开发库已是学校场景，不要执行。

SET SESSION innodb_lock_wait_timeout = 5;
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
START TRANSACTION;

-- 前置守卫：企业必须存在，否则向主键重复插入触发 1062 中止并回滚。
DROP TEMPORARY TABLE IF EXISTS schoolize_guard;
CREATE TEMPORARY TABLE schoolize_guard (k VARCHAR(16) PRIMARY KEY);
SET @ent = (SELECT id FROM sys_enterprise WHERE status = 0 ORDER BY id LIMIT 1);
INSERT INTO schoolize_guard VALUES ('enterprise');
INSERT INTO schoolize_guard SELECT 'enterprise' FROM DUAL WHERE @ent IS NULL;
SET @changed = 0;

-- 1. 企业改名（只在名称不同时更新）
UPDATE sys_enterprise SET name = '知序大学' WHERE id = @ent AND name <> '知序大学';
SET @changed = @changed + ROW_COUNT();

-- 2. 部门改名：企业式部门按固定映射换成学校部门，成员归属（department_id）保持不变。
UPDATE sys_department SET name = '教务处'
 WHERE enterprise_id = @ent AND name = '产品研发部'
   AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM sys_department WHERE enterprise_id = @ent AND name = '教务处') x);
SET @changed = @changed + ROW_COUNT();
UPDATE sys_department SET name = '学生工作处'
 WHERE enterprise_id = @ent AND name = '市场运营部'
   AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM sys_department WHERE enterprise_id = @ent AND name = '学生工作处') x);
SET @changed = @changed + ROW_COUNT();
UPDATE sys_department SET name = '图书馆'
 WHERE enterprise_id = @ent AND name = '客户成功部'
   AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM sys_department WHERE enterprise_id = @ent AND name = '图书馆') x);
SET @changed = @changed + ROW_COUNT();
UPDATE sys_department SET name = '后勤服务中心'
 WHERE enterprise_id = @ent AND name = '财务部'
   AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM sys_department WHERE enterprise_id = @ent AND name = '后勤服务中心') x);
SET @changed = @changed + ROW_COUNT();
UPDATE sys_department SET name = '就业指导中心'
 WHERE enterprise_id = @ent AND name = '人力资源部'
   AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM sys_department WHERE enterprise_id = @ent AND name = '就业指导中心') x);
SET @changed = @changed + ROW_COUNT();

-- 3. 第六个部门：信息化办公室
INSERT INTO sys_department (enterprise_id, name, status)
SELECT @ent, '信息化办公室', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_department WHERE enterprise_id = @ent AND name = '信息化办公室');
SET @changed = @changed + ROW_COUNT();

-- 4. 补贴规则文案学校化（只改名称；历史申请按 rule_id 关联，金额与状态不动）
UPDATE rag_affair_rule SET name = '实习交通补贴（演示）'
 WHERE enterprise_id = @ent AND name = '差旅补贴（演示）'
   AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM rag_affair_rule WHERE enterprise_id = @ent AND name = '实习交通补贴（演示）') x);
SET @changed = @changed + ROW_COUNT();

-- 5. 停用企业式旧空间并软删其 4 篇企业文档：
--    这几篇是通用种子（员工差旅报销、客户方案评审等），留在库里会被问答召回，与学校场景冲突。
--    只停用+逻辑删除，不物理删除，原件与片段保留可追溯。
UPDATE rag_knowledge_space SET status = 1, permission_version = permission_version + 1
 WHERE enterprise_id = @ent AND name IN ('制度与流程', '产品与方案', '新员工指南') AND status = 0;
SET @changed = @changed + ROW_COUNT();

UPDATE rag_document SET deleted = 1, published_version_id = NULL
 WHERE enterprise_id = @ent AND deleted = 0
   AND knowledge_space_id IN (SELECT id FROM rag_knowledge_space WHERE enterprise_id = @ent
         AND name IN ('制度与流程', '产品与方案', '新员工指南'));

-- 6. 确有改名时才递增权限版本，让前端重新拉取组织与授权
UPDATE sys_enterprise SET permission_version = permission_version + 1
 WHERE id = @ent AND @changed > 0;

DROP TEMPORARY TABLE schoolize_guard;
COMMIT;

SELECT id, name, permission_version FROM sys_enterprise WHERE id = @ent;
SELECT d.id, d.name, d.status,
       (SELECT COUNT(*) FROM sys_enterprise_member m WHERE m.department_id = d.id) AS members
  FROM sys_department d WHERE d.enterprise_id = @ent ORDER BY d.id;
SELECT id, name, status, (SELECT COUNT(*) FROM rag_document x
         WHERE x.knowledge_space_id = s.id AND x.deleted = 0) AS live_documents
  FROM rag_knowledge_space s WHERE s.enterprise_id = @ent ORDER BY s.id;
SELECT id, name, version, status FROM rag_affair_rule WHERE enterprise_id = @ent ORDER BY id;
