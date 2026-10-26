-- =====================================================================
-- 访客身份与会话归属增量迁移 · 2026-10-17
-- 目标: 已有rag_qa数据库; 空库只使用backend/schema.sql, 不再执行本脚本。
-- 前置: 核对MySQL版本为8.0.16及以上, 确认CHECK约束会强制执行。
-- 执行前须备份并核对现有表定义、账号归属数据, 排除同名表/字段/索引/约束冲突。
-- 只允许人工评审后执行一次, 不接入应用启动, 不使用忽略错误继续执行的选项。
-- 两条DDL不是普通业务事务内的整体操作, 不能通过ROLLBACK撤销整个脚本。
-- 任一步失败即停止; 若建表成功而ALTER失败, 先核对实际结构, 再评审后续处理。
-- 不重建旧表、不删除数据、不建物理外键; 账号user_id及原索引保持原有含义。
-- 本脚本仅准备存储结构, 不提供访客认证、日切清理、额度或查询隔离功能。
-- =====================================================================

USE rag_qa;

CREATE TABLE rag_guest_identity
(
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '访客内部主键, 不作凭据',
    credential_hash     BINARY(32)      NOT NULL COMMENT '凭据SHA-256摘要, 禁止返回或记日志',
    issued_at_epoch_ms  BIGINT          NOT NULL COMMENT '签发时刻, UTC毫秒',
    expires_at_epoch_ms BIGINT          NOT NULL COMMENT '到期时刻, UTC毫秒',
    revoked             TINYINT         NOT NULL DEFAULT 0 COMMENT '撤销标记: 0未撤销 1已撤销',
    PRIMARY KEY (id),
    UNIQUE KEY uk_guest_credential_hash (credential_hash),
    KEY idx_guest_expiry (expires_at_epoch_ms),
    CONSTRAINT chk_guest_lifetime CHECK (
        issued_at_epoch_ms >= 0 AND expires_at_epoch_ms > issued_at_epoch_ms),
    CONSTRAINT chk_guest_revoked CHECK (revoked IN (0, 1))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='当日访客临时身份';

-- 既有账号会话新增guest_id后为NULL, 原user_id不变。
-- CHECK禁止两个归属同时有值或同时为空; 身份存在及有效期由后续service校验。
ALTER TABLE rag_conversation
    MODIFY COLUMN user_id BIGINT UNSIGNED NULL DEFAULT NULL COMMENT '账号归属, 与guest_id二选一',
    ADD COLUMN guest_id BIGINT UNSIGNED NULL DEFAULT NULL COMMENT '访客归属, 逻辑关联rag_guest_identity.id',
    ADD KEY idx_guest_id (guest_id),
    ADD CONSTRAINT chk_conversation_owner CHECK (
        (user_id IS NOT NULL AND guest_id IS NULL)
        OR (user_id IS NULL AND guest_id IS NOT NULL));
