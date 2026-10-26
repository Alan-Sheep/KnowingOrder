-- 知序企业身份与资源授权增量：MySQL 8.0.16及以上，仅对已完成01/02的旧库执行一次。
-- 执行前备份并核对目标库；不含USE，不自动执行，不回填旧会话/文档，不创建物理外键。
-- MySQL DDL会隐式提交，失败后按实际表结构人工处理，不能盲目重放。
ALTER TABLE sys_user
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 1 COMMENT '认证版本，改密或撤销全部会话时递增',
    ADD CONSTRAINT chk_user_auth_version CHECK (auth_version > 0);

ALTER TABLE rag_conversation
    ADD COLUMN enterprise_id BIGINT UNSIGNED DEFAULT NULL COMMENT '企业，旧记录保持空',
    ADD COLUMN member_id BIGINT UNSIGNED DEFAULT NULL COMMENT '所属成员，逻辑关联sys_enterprise_member.id',
    ADD KEY idx_conversation_scope (enterprise_id, member_id, user_id, id),
    ADD CONSTRAINT chk_conversation_enterprise CHECK (
        (enterprise_id IS NULL AND member_id IS NULL)
        OR (enterprise_id IS NOT NULL AND member_id IS NOT NULL AND user_id IS NOT NULL AND guest_id IS NULL));

ALTER TABLE rag_document
    ADD COLUMN enterprise_id BIGINT UNSIGNED DEFAULT NULL COMMENT '企业，旧记录保持空',
    ADD COLUMN knowledge_space_id BIGINT UNSIGNED DEFAULT NULL COMMENT '知识空间，逻辑关联rag_knowledge_space.id',
    ADD KEY idx_document_space (enterprise_id, knowledge_space_id, id),
    ADD CONSTRAINT chk_document_enterprise CHECK (
        (enterprise_id IS NULL AND knowledge_space_id IS NULL)
        OR (enterprise_id IS NOT NULL AND knowledge_space_id IS NOT NULL));

-- 以下新表创建顺序遵循逻辑关联；服务层负责跨表存在性、企业一致性及状态校验。
CREATE TABLE sys_enterprise (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '企业主键',
    name VARCHAR(128) NOT NULL COMMENT '企业名称',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0启用，1停用',
    permission_version BIGINT NOT NULL DEFAULT 1 COMMENT '整体权限变更时递增',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT chk_enterprise_status CHECK (status IN (0, 1)),
    CONSTRAINT chk_enterprise_version CHECK (permission_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业';

CREATE TABLE sys_department (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '部门主键',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '所属企业',
    name VARCHAR(128) NOT NULL COMMENT '部门名称，首版平级，不继承父部门授权',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0启用，1停用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_department_enterprise (enterprise_id, id),
    CONSTRAINT chk_department_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业直属部门';

CREATE TABLE sys_enterprise_member (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '成员主键，与账号主键分开',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '所属企业',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '系统账号',
    department_id BIGINT UNSIGNED DEFAULT NULL COMMENT '当前直属部门，不推导层级授权',
    role VARCHAR(16) NOT NULL DEFAULT 'EMPLOYEE' COMMENT 'EMPLOYEE员工，ADMIN账号管理员；不隐含私有资源权限',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0有效，1停用，2离职',
    permission_version BIGINT NOT NULL DEFAULT 1 COMMENT '角色、部门或状态改变时递增',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_enterprise_user (enterprise_id, user_id),
    KEY idx_member_user (user_id, enterprise_id),
    CONSTRAINT chk_member_status CHECK (status IN (0, 1, 2)),
    CONSTRAINT chk_member_role CHECK (role IN ('EMPLOYEE', 'ADMIN')),
    CONSTRAINT chk_member_version CHECK (permission_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业成员关系';

CREATE TABLE rag_knowledge_space (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '空间主键',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '所属企业',
    name VARCHAR(128) NOT NULL COMMENT '知识空间名称',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0启用，1停用',
    permission_version BIGINT NOT NULL DEFAULT 1 COMMENT '授权改变时递增，用于后续缓存失效',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_space_enterprise (enterprise_id, id),
    CONSTRAINT chk_space_status CHECK (status IN (0, 1)),
    CONSTRAINT chk_space_version CHECK (permission_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业知识空间';

CREATE TABLE rag_knowledge_grant (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '授权主键',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '授权与目标必须属于同一企业',
    knowledge_space_id BIGINT UNSIGNED NOT NULL COMMENT '授权空间',
    member_id BIGINT UNSIGNED DEFAULT NULL COMMENT '指定成员，与部门二选一',
    department_id BIGINT UNSIGNED DEFAULT NULL COMMENT '指定直属部门，与成员二选一',
    permission VARCHAR(16) NOT NULL COMMENT 'READ可读，MAINTAIN含读取；本批未开放文档写入口',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0有效，1撤销',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_grant_member (enterprise_id, knowledge_space_id, member_id),
    UNIQUE KEY uk_grant_department (enterprise_id, knowledge_space_id, department_id),
    CONSTRAINT chk_grant_target CHECK ((member_id IS NOT NULL AND department_id IS NULL)
        OR (member_id IS NULL AND department_id IS NOT NULL)),
    CONSTRAINT chk_grant_permission CHECK (permission IN ('READ', 'MAINTAIN')),
    CONSTRAINT chk_grant_status CHECK (status IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识空间明确授权';

-- 只保存任务与会话/消息的归属锚点，未来执行器事务写入；不代表后台任务已经实现。
CREATE TABLE rag_task_reference (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务编号，与未来执行记录保持一致',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '所属企业',
    conversation_id BIGINT UNSIGNED NOT NULL COMMENT '所属会话',
    message_id BIGINT UNSIGNED NOT NULL COMMENT '原始消息，必须属于同一会话',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_task_conversation (enterprise_id, conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务归属锚点，不保存执行结果';
