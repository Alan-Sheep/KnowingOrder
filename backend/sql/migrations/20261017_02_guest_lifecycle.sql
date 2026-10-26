-- =====================================================================
-- 访客跨日状态与期限增量迁移 · 2026-10-17
-- 目标: 已按20261017_01_guest_identity.sql建立第一版结构的rag_qa数据库。
-- 空库仅执行最终backend/schema.sql, 不再执行01或本脚本。
-- 执行前核对MySQL版本、表定义、原约束、数据及同名字段/约束冲突, 备份并人工评审。
-- 迁移期间暂停访客签发、写入及相关任务, 防止新旧结构或中间回填状态被并发使用。
-- 本脚本只允许执行一次, 不接入应用启动; 失败即停止, 不忽略错误继续执行。
-- DDL与数据回填不能由普通事务整体回滚; 中途失败须核对实际结构和数据后再评审处理。
-- 原revoked 0/1映射为status 0/1, 含义保留; 原凭据摘要、签发/到期时间及主键不变。
-- 旧记录的新受理/执行期限均回填为原到期时刻, 阅读窗口为0, 不补发跨日或阅读权限。
-- 仅更新访客表; 不删除数据、不建物理外键、不修改账号、会话、消息、文档及分块表。
-- 结构不等于授权实现: 服务层仍须复核状态、时间、任务归属和预算。
-- =====================================================================

USE rag_qa;

-- 第一步: 旧撤销值保留, 新增期限暂允许NULL, 供下一步保守回填。
ALTER TABLE rag_guest_identity
    DROP CHECK chk_guest_revoked,
    CHANGE COLUMN revoked status TINYINT NOT NULL DEFAULT 0 COMMENT '生命周期: 0正常 1撤销 2收尾 3到期',
    ADD COLUMN accept_until_epoch_ms BIGINT NULL COMMENT '新问题受理截止, UTC毫秒, 北京时间次日零点' AFTER issued_at_epoch_ms,
    ADD COLUMN drain_until_epoch_ms BIGINT NULL COMMENT '已受理任务执行硬上限, UTC毫秒, 不延长原任务时限' AFTER accept_until_epoch_ms,
    ADD COLUMN result_read_window_ms BIGINT NOT NULL DEFAULT 0 COMMENT '结果阅读窗口毫秒, 新身份初值10分钟, 0仅用于兼容旧记录' AFTER status,
    ADD CONSTRAINT chk_guest_status CHECK (status IN (0, 1, 2, 3)),
    ADD CONSTRAINT chk_guest_read_window CHECK (result_read_window_ms >= 0);

-- 第二步: 不扩大原有权限, 不改变原expires_at或撤销值, 不自动重签身份。
-- 窗口为0的记录不得再受理新任务, 已有结果仅沿用原授权期限, 由后续服务层保证。
UPDATE rag_guest_identity
SET accept_until_epoch_ms = expires_at_epoch_ms,
    drain_until_epoch_ms = expires_at_epoch_ms
WHERE accept_until_epoch_ms IS NULL
  AND drain_until_epoch_ms IS NULL;

-- 第三步: 收紧非空与时间顺序约束; 600000只作为未来新记录的初始默认值。
-- 签发服务须显式保存当时的配置快照并校验正值; 不能用默认值把旧身份当作新身份。
ALTER TABLE rag_guest_identity
    DROP CHECK chk_guest_lifetime,
    MODIFY COLUMN accept_until_epoch_ms BIGINT NOT NULL COMMENT '新问题受理截止, UTC毫秒, 北京时间次日零点',
    MODIFY COLUMN drain_until_epoch_ms BIGINT NOT NULL COMMENT '已受理任务执行硬上限, UTC毫秒, 不延长原任务时限',
    MODIFY COLUMN expires_at_epoch_ms BIGINT NOT NULL COMMENT '身份最迟失效硬上限, UTC毫秒, 不代表通用读取权限',
    MODIFY COLUMN result_read_window_ms BIGINT NOT NULL DEFAULT 600000 COMMENT '结果阅读窗口毫秒, 新身份初值10分钟, 0仅用于兼容旧记录',
    ADD CONSTRAINT chk_guest_time_bounds CHECK (
        issued_at_epoch_ms >= 0
        AND issued_at_epoch_ms < accept_until_epoch_ms
        AND accept_until_epoch_ms <= drain_until_epoch_ms
        AND drain_until_epoch_ms <= expires_at_epoch_ms);
