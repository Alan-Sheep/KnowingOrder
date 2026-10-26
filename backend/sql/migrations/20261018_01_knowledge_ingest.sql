-- 知识管理与文字入库增量；仅用于已经完成20261017_03迁移的MySQL 8.0.16及以上开发库。
-- 先备份、确认DATABASE()与旧定义，再逐段执行。DDL不保证整脚本事务回滚。
-- 禁止重放schema.sql；若中途失败，先SHOW CREATE TABLE核对，不盲目重跑。

ALTER TABLE rag_document
    ADD COLUMN owner_member_id BIGINT UNSIGNED DEFAULT NULL COMMENT '知识负责人，旧记录为空',
    ADD COLUMN published_version_id BIGINT UNSIGNED DEFAULT NULL COMMENT '当前发布版本逻辑指针',
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 COMMENT '并发修改版本',
    ADD CONSTRAINT chk_document_revision CHECK(revision > 0);

ALTER TABLE rag_chunk
    ADD COLUMN version_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '文档版本，历史记录为0',
    ADD COLUMN start_offset INT DEFAULT NULL COMMENT '原文UTF-16起始位置，含',
    ADD COLUMN end_offset INT DEFAULT NULL COMMENT '原文UTF-16结束位置，不含',
    ADD COLUMN start_line INT DEFAULT NULL COMMENT '起始行，从1开始',
    ADD COLUMN end_line INT DEFAULT NULL COMMENT '结束行，从1开始',
    DROP INDEX uk_doc_chunk,
    ADD UNIQUE KEY uk_doc_chunk(document_id,version_id,chunk_index);

CREATE TABLE rag_document_version (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '版本主键',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '所属企业',
    knowledge_space_id BIGINT UNSIGNED NOT NULL COMMENT '所属空间',
    document_id BIGINT UNSIGNED NOT NULL COMMENT '所属逻辑文档',
    version_no INT NOT NULL COMMENT '同文档递增版本号，从1开始',
    upload_request_id CHAR(36) NOT NULL COMMENT '客户端上传请求UUID，重传相同内容返回原版本',
    original_name VARCHAR(255) NOT NULL COMMENT '安全原文件名，不作磁盘路径',
    storage_key CHAR(36) NOT NULL COMMENT '服务端随机原件键，禁止返回前端',
    content_sha256 CHAR(64) NOT NULL COMMENT '原件完整性摘要，不作权限凭据',
    request_sha256 CHAR(64) NOT NULL COMMENT '上传参数及内容指纹，防止同幂等键被换参复用',
    file_size BIGINT NOT NULL COMMENT '原文件字节数',
    parse_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待解析，READY片段就绪，FAILED失败',
    publication_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT草稿，PUBLISHED发布，WITHDRAWN撤回，DELETED软删除',
    chunk_count INT NOT NULL DEFAULT 0 COMMENT '该版本实际提交的片段数',
    failure_code VARCHAR(32) DEFAULT NULL COMMENT '安全失败分类，不保存原文或异常消息',
    valid_from_ms BIGINT DEFAULT NULL COMMENT 'UTC毫秒，空表示无开始限制',
    valid_until_ms BIGINT DEFAULT NULL COMMENT 'UTC毫秒，空表示无结束限制；结束点不包含',
    created_by_member BIGINT UNSIGNED NOT NULL COMMENT '提交版本的企业成员',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '版本创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '版本更新时间',
    PRIMARY KEY(id),
    UNIQUE KEY uk_version_no(document_id,version_no),
    UNIQUE KEY uk_upload_request(enterprise_id,knowledge_space_id,upload_request_id),
    KEY idx_version_scope(enterprise_id,knowledge_space_id,document_id,id),
    CONSTRAINT chk_version_no CHECK(version_no > 0),
    CONSTRAINT chk_version_size CHECK(file_size > 0 AND file_size <= 1048576),
    CONSTRAINT chk_version_chunks CHECK(chunk_count >= 0),
    CONSTRAINT chk_version_parse CHECK(parse_status IN ('PENDING','READY','FAILED')),
    CONSTRAINT chk_version_publication CHECK(publication_status IN ('DRAFT','PUBLISHED','WITHDRAWN','DELETED')),
    CONSTRAINT chk_version_dates CHECK((valid_from_ms IS NULL OR valid_from_ms >= 0)
        AND (valid_until_ms IS NULL OR valid_until_ms >= 0)
        AND (valid_from_ms IS NULL OR valid_until_ms IS NULL OR valid_from_ms < valid_until_ms))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业文档不可变原件与版本生命周期';

-- 只读核对：不自动发布旧文档、不补造版本、不发送企业资料到外部。
SHOW CREATE TABLE rag_document;
SHOW CREATE TABLE rag_chunk;
SHOW CREATE TABLE rag_document_version;
