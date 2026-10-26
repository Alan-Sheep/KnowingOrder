#!/usr/bin/env bash
# 云端演示库学校化：企业名 → 知序大学，5 个企业部门 → 6 个学校部门，停用企业式旧空间。
#
# 用法（本地执行，一条命令完成上传+备份+执行）：
#   scp backend/deploy/schoolize.sh root@8.155.137.88:/tmp/ && ssh root@8.155.137.88 "bash /tmp/schoolize.sh"
#
# 幂等：全部条件化 UPDATE/INSERT，重复执行空跑。先备份再改，失败即停。

set -uo pipefail

DB_NAME=${DB_NAME:-rag_qa}
BACKUP_DIR=/root/backup
STAMP=$(date +%Y%m%d-%H%M%S)

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$1"; }
ok()   { printf '    \033[32m✓\033[0m %s\n' "$1"; }
die()  { printf '    \033[31m✗\033[0m %s\n' "$1"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "请用 root 执行（sudo bash $0）"

MYSQL=(mysql -uroot)
"${MYSQL[@]}" -e "SELECT 1" >/dev/null 2>&1 || die "root 无法免密连接 MySQL"

# ---------------------------------------------------------------- 1 备份
step "1/3 备份数据库 $DB_NAME"
mkdir -p "$BACKUP_DIR"
BACKUP="$BACKUP_DIR/${DB_NAME}-before-schoolize-${STAMP}.sql"
mysqldump -uroot --single-transaction --routines --triggers "$DB_NAME" > "$BACKUP" || die "备份失败，未做任何修改"
ok "备份：$BACKUP（$(du -h "$BACKUP" | cut -f1)）"

# ---------------------------------------------------------------- 2 学校化
step "2/3 执行学校化 SQL"
"${MYSQL[@]}" "$DB_NAME" <<'SQL' || die "SQL 执行失败，事务已回滚；备份见上一步"
SET SESSION innodb_lock_wait_timeout = 5;
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS schoolize_guard;
CREATE TEMPORARY TABLE schoolize_guard (k VARCHAR(16) PRIMARY KEY);
SET @ent = (SELECT id FROM sys_enterprise WHERE status = 0 ORDER BY id LIMIT 1);
INSERT INTO schoolize_guard VALUES ('enterprise');
INSERT INTO schoolize_guard SELECT 'enterprise' FROM DUAL WHERE @ent IS NULL;
SET @changed = 0;

-- 1. 企业改名
UPDATE sys_enterprise SET name = '知序大学' WHERE id = @ent AND name <> '知序大学';
SET @changed = @changed + ROW_COUNT();

-- 2. 部门改名（成员 department_id 不变，归属自动跟随）
UPDATE sys_department SET name = '教务处' WHERE enterprise_id = @ent AND name = '产品研发部'
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM sys_department WHERE enterprise_id = @ent AND name = '教务处') x);
SET @changed = @changed + ROW_COUNT();
UPDATE sys_department SET name = '学生工作处' WHERE enterprise_id = @ent AND name = '市场运营部'
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM sys_department WHERE enterprise_id = @ent AND name = '学生工作处') x);
SET @changed = @changed + ROW_COUNT();
UPDATE sys_department SET name = '图书馆' WHERE enterprise_id = @ent AND name = '客户成功部'
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM sys_department WHERE enterprise_id = @ent AND name = '图书馆') x);
SET @changed = @changed + ROW_COUNT();
UPDATE sys_department SET name = '后勤服务中心' WHERE enterprise_id = @ent AND name = '财务部'
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM sys_department WHERE enterprise_id = @ent AND name = '后勤服务中心') x);
SET @changed = @changed + ROW_COUNT();
UPDATE sys_department SET name = '就业指导中心' WHERE enterprise_id = @ent AND name = '人力资源部'
  AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM sys_department WHERE enterprise_id = @ent AND name = '就业指导中心') x);
SET @changed = @changed + ROW_COUNT();

-- 3. 第六个部门
INSERT INTO sys_department (enterprise_id, name, status)
SELECT @ent, '信息化办公室', 0 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_department WHERE enterprise_id = @ent AND name = '信息化办公室');
SET @changed = @changed + ROW_COUNT();

-- 4. 补贴规则文案学校化（金额与状态不动）
UPDATE rag_affair_rule SET name = '实习交通补贴（演示）'
 WHERE enterprise_id = @ent AND name = '差旅补贴（演示）'
   AND NOT EXISTS (SELECT 1 FROM (SELECT id FROM rag_affair_rule WHERE enterprise_id = @ent AND name = '实习交通补贴（演示）') x);
SET @changed = @changed + ROW_COUNT();

-- 5. 停用企业式旧空间并软删其企业文档（避免问答召回「员工差旅报销」等无关内容）
UPDATE rag_knowledge_space SET status = 1, permission_version = permission_version + 1
 WHERE enterprise_id = @ent AND name IN ('制度与流程', '产品与方案', '新员工指南') AND status = 0;
SET @changed = @changed + ROW_COUNT();
UPDATE rag_document SET deleted = 1, published_version_id = NULL
 WHERE enterprise_id = @ent AND deleted = 0
   AND knowledge_space_id IN (SELECT id FROM rag_knowledge_space WHERE enterprise_id = @ent
         AND name IN ('制度与流程', '产品与方案', '新员工指南'));

-- 6. 平台管理员补昵称（成员目录/分摊下拉显示中文姓名；昵称优先、为空回退登录名）
UPDATE sys_user SET nickname = '系统管理员'
 WHERE username = 'admin' AND (nickname IS NULL OR TRIM(nickname) = '');

-- 7. 确有变化才递增权限版本
UPDATE sys_enterprise SET permission_version = permission_version + 1 WHERE id = @ent AND @changed > 0;

DROP TEMPORARY TABLE schoolize_guard;
COMMIT;
SQL
ok "SQL 提交完成"

# ---------------------------------------------------------------- 3 核验
step "3/3 核验结果"
"${MYSQL[@]}" "$DB_NAME" -t <<'SQL'
SET NAMES utf8mb4;
SELECT id, name, permission_version FROM sys_enterprise WHERE status = 0;
SELECT d.id, d.name, d.status,
       (SELECT COUNT(*) FROM sys_enterprise_member m WHERE m.department_id = d.id) AS members
  FROM sys_department d WHERE d.enterprise_id = (SELECT id FROM sys_enterprise WHERE status = 0 ORDER BY id LIMIT 1)
 ORDER BY d.id;
SELECT s.id, s.name, s.status,
       (SELECT COUNT(*) FROM rag_document x WHERE x.knowledge_space_id = s.id AND x.deleted = 0) AS live_docs
  FROM rag_knowledge_space s
 WHERE s.enterprise_id = (SELECT id FROM sys_enterprise WHERE status = 0 ORDER BY id LIMIT 1)
 ORDER BY s.id;
SQL

printf '\n完成。请让浏览器强刷（Ctrl+Shift+R）后重新登录，左上角应显示「知序大学」。\n备份：%s\n' "$BACKUP"
