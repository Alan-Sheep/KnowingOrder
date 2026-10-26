-- 员工事务增量：凭证与补贴、参与确认与财务协同（G1—G5 最小闭环）。
-- MySQL 8.0.16 及以上；先备份并确认 USE rag_qa，禁止重放既有 schema。
-- 不建物理外键，跨表企业一致性、状态流转与并发由服务层和条件更新校验。
-- DDL 隐式提交，失败后按实际表结构逐表人工核对，不能盲目重跑。

-- 新增财务协同角色；服务层角色白名单需同步放开 FINANCE，角色本身不代表可读私有知识。
ALTER TABLE sys_enterprise_member
    DROP CHECK chk_member_role,
    ADD CONSTRAINT chk_member_role CHECK (role IN ('EMPLOYEE', 'ADMIN', 'FINANCE'));

-- 制度版本：资格、额度与审批要求由明确规则决定，不交给模型裁定。
CREATE TABLE rag_affair_rule (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '规则主键',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '所属企业',
    name VARCHAR(128) NOT NULL COMMENT '规则名称，同企业同名多版本',
    version INT NOT NULL COMMENT '规则版本，生效后只增不改',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT草稿/ACTIVE生效/RETIRED停用',
    period VARCHAR(8) NOT NULL DEFAULT 'MONTH' COMMENT '额度周期MONTH/QUARTER/YEAR/NONE',
    member_limit_cents BIGINT DEFAULT NULL COMMENT '每成员周期额度(分)，NULL表示不限',
    application_limit_cents BIGINT NOT NULL COMMENT '单笔补贴上限(分)',
    requires_manager TINYINT NOT NULL DEFAULT 1 COMMENT '0不需要主管审批，1需要',
    effective_from DATETIME NOT NULL COMMENT '生效时间',
    effective_to DATETIME DEFAULT NULL COMMENT '失效时间，NULL表示不自动失效',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '创建成员',
    created_ms BIGINT NOT NULL COMMENT '创建UTC毫秒',
    updated_ms BIGINT NOT NULL COMMENT '更新UTC毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_affair_rule_version (enterprise_id, name, version),
    KEY idx_affair_rule_active (enterprise_id, status, effective_from),
    CONSTRAINT chk_affair_rule_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT chk_affair_rule_period CHECK (period IN ('MONTH', 'QUARTER', 'YEAR', 'NONE')),
    CONSTRAINT chk_affair_rule_amount CHECK (application_limit_cents > 0 AND (member_limit_cents IS NULL OR member_limit_cents > 0)),
    CONSTRAINT chk_affair_rule_manager CHECK (requires_manager IN (0, 1)),
    CONSTRAINT chk_affair_rule_version CHECK (version > 0),
    CONSTRAINT chk_affair_rule_window CHECK (effective_to IS NULL OR effective_to > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='员工事务补贴规则版本';

-- 申请主记录：保存当前版本指针、履约与财务状态，不保存任何支付凭据。
CREATE TABLE rag_affair_application (
    id CHAR(36) NOT NULL COMMENT '客户端请求UUID，重复提交必须复用',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '企业范围',
    applicant_member_id BIGINT UNSIGNED NOT NULL COMMENT '申请人成员',
    rule_id BIGINT UNSIGNED NOT NULL COMMENT '受理时规则逻辑关联',
    rule_version INT NOT NULL COMMENT '受理时规则版本，后续改规则不追溯',
    platform VARCHAR(32) NOT NULL COMMENT '外部渠道标识；MOCK 不进入财务事实',
    external_order_no VARCHAR(64) DEFAULT NULL COMMENT '外部订单号，可空；有值时用于防重复申请',
    occurred_at DATETIME NOT NULL COMMENT '实际消费时间',
    amount_cents BIGINT NOT NULL COMMENT '本次净支出金额(分)',
    payer_member_id BIGINT UNSIGNED NOT NULL COMMENT '实际垫付人',
    payee_member_id BIGINT UNSIGNED DEFAULT NULL COMMENT '最终收款人，企业规则确认前为空',
    status VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED/CONFIRMING/VERIFIED/PENDING_SETTLEMENT/SETTLED/REJECTED/CANCELLED',
    current_version INT NOT NULL DEFAULT 1 COMMENT '当前申请版本，金额或分摊变化时递增',
    quota_period VARCHAR(8) NOT NULL COMMENT '额度归属周期标识，如2026-10',
    reserved_cents BIGINT NOT NULL DEFAULT 0 COMMENT '当前占用额度(分)，取消或拒绝后释放',
    manager_approved_by BIGINT UNSIGNED DEFAULT NULL COMMENT '主管审批成员',
    manager_approved_ms BIGINT DEFAULT NULL COMMENT '主管审批UTC毫秒',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '乐观并发版本',
    created_ms BIGINT NOT NULL COMMENT '提交UTC毫秒',
    updated_ms BIGINT NOT NULL COMMENT '更新UTC毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_affair_application_order (enterprise_id, applicant_member_id, platform, external_order_no),
    KEY idx_affair_application_owner (enterprise_id, applicant_member_id, created_ms),
    KEY idx_affair_application_finance (enterprise_id, status, updated_ms),
    CONSTRAINT chk_affair_application_status CHECK (status IN ('SUBMITTED', 'CONFIRMING', 'VERIFIED', 'PENDING_SETTLEMENT', 'SETTLED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_affair_application_amount CHECK (amount_cents > 0 AND reserved_cents >= 0),
    CONSTRAINT chk_affair_application_version CHECK (current_version > 0 AND revision > 0),
    CONSTRAINT chk_affair_application_quota CHECK (quota_period <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='补贴申请当前状态与版本指针';

-- 申请版本快照：旧版本保留，受影响员工只对当前版本确认。
CREATE TABLE rag_affair_application_version (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '版本主键',
    application_id CHAR(36) NOT NULL COMMENT '所属申请逻辑关联',
    version INT NOT NULL COMMENT '申请版本号',
    amount_cents BIGINT NOT NULL COMMENT '该版本金额(分)',
    participants_json LONGTEXT NOT NULL COMMENT '参与人与分摊快照(JSON文本)，服务层校验后写入',
    snapshot_json LONGTEXT NOT NULL COMMENT '完整业务快照，含规则与渠道事实',
    change_reason VARCHAR(200) NOT NULL COMMENT '变更原因，首次提交填创建',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '发起变更的成员',
    created_ms BIGINT NOT NULL COMMENT '创建UTC毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_affair_version (application_id, version),
    CONSTRAINT chk_affair_version_no CHECK (version > 0),
    CONSTRAINT chk_affair_version_amount CHECK (amount_cents > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='补贴申请不可变版本快照';

-- 参与确认：以申请版本为界，旧确认不能批准新内容。
CREATE TABLE rag_affair_participant (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '确认记录主键',
    application_id CHAR(36) NOT NULL COMMENT '所属申请逻辑关联',
    version INT NOT NULL COMMENT '对应申请版本',
    member_id BIGINT UNSIGNED NOT NULL COMMENT '被邀请参与成员',
    share_cents BIGINT NOT NULL COMMENT '本人分摊金额(分)',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待确认/CONFIRMED已确认/REJECTED已拒绝',
    confirmed_ms BIGINT DEFAULT NULL COMMENT '确认或拒绝UTC毫秒',
    note VARCHAR(200) DEFAULT NULL COMMENT '纯文本说明，不存敏感正文',
    PRIMARY KEY (id),
    UNIQUE KEY uk_affair_participant (application_id, version, member_id),
    KEY idx_affair_participant_member (member_id, status),
    CONSTRAINT chk_affair_participant_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')),
    CONSTRAINT chk_affair_participant_share CHECK (share_cents >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多人订单参与确认（按版本）';

-- 原始凭证：只保存原件与完整性摘要，不做 OCR，不上传云端识别。
CREATE TABLE rag_affair_receipt (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '凭证主键',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '企业范围',
    application_id CHAR(36) NOT NULL COMMENT '所属申请逻辑关联',
    original_name VARCHAR(128) NOT NULL COMMENT '原始文件名，仅作展示与下载名',
    storage_path VARCHAR(512) NOT NULL COMMENT '私有存储相对路径，禁止对外拼接直链',
    content_type VARCHAR(64) NOT NULL COMMENT '声明类型，仅白名单图片与PDF',
    byte_size BIGINT NOT NULL COMMENT '字节数',
    sha256 CHAR(64) NOT NULL COMMENT '原件摘要，用于完整性与重复校验',
    uploaded_by BIGINT UNSIGNED NOT NULL COMMENT '上传成员',
    created_ms BIGINT NOT NULL COMMENT '上传UTC毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_affair_receipt_digest (application_id, sha256),
    KEY idx_affair_receipt_app (application_id, created_ms),
    CONSTRAINT chk_affair_receipt_size CHECK (byte_size > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='补贴申请原始凭证原件';

-- 额度账本：同一成员同一周期一行，条件更新保证并发不超额。
CREATE TABLE rag_affair_quota (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '额度行主键',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '企业范围',
    member_id BIGINT UNSIGNED NOT NULL COMMENT '额度归属成员',
    period VARCHAR(8) NOT NULL COMMENT '周期标识，如2026-10',
    limit_cents BIGINT DEFAULT NULL COMMENT '当期额度(分)，NULL表示不限',
    used_cents BIGINT NOT NULL DEFAULT 0 COMMENT '已结算占用(分)',
    reserved_cents BIGINT NOT NULL DEFAULT 0 COMMENT '在途占用(分)',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '乐观版本，每次占用或释放递增',
    updated_ms BIGINT NOT NULL COMMENT '更新UTC毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_affair_quota (enterprise_id, member_id, period),
    CONSTRAINT chk_affair_quota_amount CHECK ((limit_cents IS NULL OR limit_cents > 0) AND used_cents >= 0 AND reserved_cents >= 0),
    CONSTRAINT chk_affair_quota_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成员周期补贴额度账本';

-- 财务结算：只表示系统内核算状态，不等于工资已实际发放。
CREATE TABLE rag_affair_settlement (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '结算主键',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '企业范围',
    application_id CHAR(36) NOT NULL COMMENT '所属申请逻辑关联',
    application_version INT NOT NULL COMMENT '结算依据的申请版本',
    rule_id BIGINT UNSIGNED NOT NULL COMMENT '结算依据的规则',
    amount_cents BIGINT NOT NULL COMMENT '结算金额(分)',
    payee_member_id BIGINT UNSIGNED NOT NULL COMMENT '最终收款人，结算前必须明确',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待审批/APPROVED已批准/SETTLED已结算/RETURNED已退回/VOID作废',
    finance_member_id BIGINT UNSIGNED DEFAULT NULL COMMENT '处理财务成员',
    approved_ms BIGINT DEFAULT NULL COMMENT '批准UTC毫秒',
    settled_ms BIGINT DEFAULT NULL COMMENT '标记结算UTC毫秒，不代表已发放',
    note VARCHAR(500) DEFAULT NULL COMMENT '核算说明，不存银行账号等敏感凭据',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '乐观并发版本',
    created_ms BIGINT NOT NULL COMMENT '创建UTC毫秒',
    updated_ms BIGINT NOT NULL COMMENT '更新UTC毫秒',
    PRIMARY KEY (id),
    UNIQUE KEY uk_affair_settlement_application (application_id),
    KEY idx_affair_settlement_queue (enterprise_id, status, created_ms),
    CONSTRAINT chk_affair_settlement_status CHECK (status IN ('PENDING', 'APPROVED', 'SETTLED', 'RETURNED', 'VOID')),
    CONSTRAINT chk_affair_settlement_amount CHECK (amount_cents > 0 AND revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='财务协同结算记录';

-- 退款与调整：已结算后发生退款只追加调整，不改原结算事实。
CREATE TABLE rag_affair_adjustment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '调整主键',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '企业范围',
    application_id CHAR(36) NOT NULL COMMENT '所属申请逻辑关联',
    settlement_id BIGINT UNSIGNED DEFAULT NULL COMMENT '关联结算，未结算时为NULL',
    kind VARCHAR(16) NOT NULL COMMENT 'REFUND_FULL全额退款/REFUND_PARTIAL部分退款/REVERSAL冲正/CORRECTION更正',
    amount_cents BIGINT NOT NULL COMMENT '调整金额(分)，恒为正数，方向由类型表达',
    reason VARCHAR(200) NOT NULL COMMENT '调整原因，纯文本',
    operator_member_id BIGINT UNSIGNED NOT NULL COMMENT '操作成员',
    created_ms BIGINT NOT NULL COMMENT '创建UTC毫秒',
    PRIMARY KEY (id),
    KEY idx_affair_adjustment_app (application_id, created_ms),
    CONSTRAINT chk_affair_adjustment_kind CHECK (kind IN ('REFUND_FULL', 'REFUND_PARTIAL', 'REVERSAL', 'CORRECTION')),
    CONSTRAINT chk_affair_adjustment_amount CHECK (amount_cents > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='补贴退款与结算调整记录';
