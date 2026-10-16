-- =====================================================================
-- RAG 智能问答系统 · 预留库表结构(MySQL 8.0 / InnoDB / utf8mb4)
-- 说明: 本脚本仅为后续模块预留表结构, 当前骨架未接入数据源、不建物理外键;
--       表间关联靠索引字段做逻辑关联, 便于后续分库/软删扩展。
-- 关联链路:
--   sys_user 1 -- N rag_document      (created_by -> sys_user.id)
--   sys_user 1 -- N rag_conversation   (user_id   -> sys_user.id)
--   rag_document 1 -- N rag_chunk       (document_id -> rag_document.id)
--   rag_conversation 1 -- N rag_message (conversation_id -> rag_conversation.id)
-- =====================================================================
-- 一、表作用
--   1. sys_user         系统用户: 账号、密码散列、状态。问答系统与外部的身份锚点
--   2. rag_document     知识库文档: 原始文档的元信息与解析状态, 检索结果的溯源起点
--   3. rag_chunk        文档分块: 文档切分后的文本片段, 每个片段是检索的最小命中单位
--   4. rag_conversation 问答会话: 一次连续对话的容器, 决定多轮上下文的边界
--   5. rag_message      对话消息: 每轮 user/assistant 消息, 会话的历史记录
--
--   向量(embedding)不进 rag_chunk, 由专用向量库存储, 本表只留文本与统计字段。
--
-- 二、关联字段(逻辑关联, 均为 BIGINT UNSIGNED 且不建物理外键)
--   rag_document.created_by      -> sys_user.id          文档归属者
--   rag_conversation.user_id     -> sys_user.id          会话归属者
--   rag_chunk.document_id        -> rag_document.id      块所属文档
--   rag_message.conversation_id  -> rag_conversation.id  消息所属会话
--
--   不建物理外键的原因: 避免插入/删除互相加锁影响写入, 且便于后续分库与数据归档;
--   引用完整性由 service 层保证, 不存在对应主记录时业务侧拒绝写入。
--
-- 三、索引清单与用途
--   sys_user
--     uk_username(username) 唯一  登录时按用户名查, 并保证账号不重复
--     idx_email(email)      普通  按邮箱检索
--   rag_document
--     idx_status(status)    普通  定时任务扫描待解析(0)/失败(3)的文档队列
--     idx_created_by        普通  "我的文档"列表按上传人过滤
--   rag_chunk
--     uk_doc_chunk(document_id, chunk_index) 唯一+联合
--       保证同文档内块序号不重复(防重复切分写入); 最左前缀 document_id 可直接
--       用于"按文档拉取全部块"的查询, 因此不再单建 idx_document_id
--   rag_conversation
--     idx_user_id           普通  会话列表按用户分页
--   rag_message
--     idx_conv_time(conversation_id, created_at) 联合
--       按会话拉取历史, 记录天然按时间正序返回, 省去额外排序
--
-- 四、字段约定
--   逻辑删除: sys_user / rag_document / rag_conversation 有 deleted 列(0未删除 1已删除);
--             rag_chunk / rag_message 无该列, 属随父记录存亡的从属数据。
--             注意: 逻辑删除不会释放唯一索引, 已软删的用户名仍占用 uk_username,
--             后续如需支持重用用户名, 需改用 (username, deleted) 联合唯一或加删除时间戳。
--   时间列:   created_at 由 DEFAULT CURRENT_TIMESTAMP 维护,
--             updated_at 由 ON UPDATE CURRENT_TIMESTAMP 自动刷新, 应用层不赋值。
--   计数列:   rag_document.chunk_count 是冗余统计, 需在分块写入后同步更新,
--             不要与 SELECT COUNT(*) FROM rag_chunk 的结果长期不一致。
--   字符集:   全库 utf8mb4 / utf8mb4_unicode_ci, 与 mysqldump 及应用连接串保持一致。
--
-- 五、与后端实体的对应
--   com.ragqa.domain.entity.SysUser / RagDocument / RagChunk / RagConversation / RagMessage
--   字段注释中的枚举取值同步自 com.ragqa.common.Constants, 改动需两处一起改。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS rag_qa
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE rag_qa;

-- ---------------------------------------------------------------------
-- 1. sys_user 系统用户表: 保存问答系统使用者的账号与基本资料
-- ---------------------------------------------------------------------
CREATE TABLE sys_user
(
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    username      VARCHAR(64)     NOT NULL                COMMENT '登录用户名(唯一)',
    password_hash VARCHAR(128)    NOT NULL                COMMENT '密码散列值(BCrypt等), 禁止存储明文',
    nickname      VARCHAR(64)              DEFAULT NULL    COMMENT '用户昵称',
    email         VARCHAR(128)             DEFAULT NULL    COMMENT '邮箱地址',
    status        TINYINT         NOT NULL DEFAULT 0      COMMENT '账号状态: 0正常 1禁用',
    deleted       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除标记: 0未删除 1已删除',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 唯一索引: 保证登录名不重复, 登录查询直接命中
    UNIQUE KEY uk_username (username),
    -- 普通索引: 支持按邮箱检索/找回账号
    KEY idx_email (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='系统用户表';

-- ---------------------------------------------------------------------
-- 2. rag_document 知识库文档表: 记录上传/采集的原始文档及其解析状态
-- ---------------------------------------------------------------------
CREATE TABLE rag_document
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    title       VARCHAR(255)    NOT NULL                COMMENT '文档标题',
    source_type VARCHAR(32)     NOT NULL                COMMENT '来源类型: UPLOAD本地上传 / LINK外部链接',
    file_path   VARCHAR(512)             DEFAULT NULL    COMMENT '文件存储路径(上传类文档使用)',
    file_size   BIGINT                   DEFAULT NULL    COMMENT '文件大小(字节)',
    status      TINYINT         NOT NULL DEFAULT 0      COMMENT '解析状态: 0待解析 1解析中 2完成 3失败',
    chunk_count INT             NOT NULL DEFAULT 0      COMMENT '已分块数量(冗余统计, 便于列表展示)',
    created_by  BIGINT UNSIGNED          DEFAULT NULL    COMMENT '上传人ID, 逻辑关联 sys_user.id',
    deleted     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除标记: 0未删除 1已删除',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 普通索引: 按解析状态筛选待处理队列(定时任务扫描 0/3 状态)
    KEY idx_status (status),
    -- 普通索引: 按上传人查询自己的文档列表
    KEY idx_created_by (created_by)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='知识库文档表';

-- ---------------------------------------------------------------------
-- 3. rag_chunk 文档分块表: 存储文档切分后的文本片段
--    说明: 向量(embedding)后续接入专用向量库存储, 本表不建向量列、不建向量索引
-- ---------------------------------------------------------------------
CREATE TABLE rag_chunk
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    document_id  BIGINT UNSIGNED NOT NULL                COMMENT '所属文档ID, 逻辑关联 rag_document.id',
    chunk_index  INT             NOT NULL                COMMENT '块在原文档中的序号(从0开始)',
    content      MEDIUMTEXT               DEFAULT NULL    COMMENT '分块纯文本内容',
    token_count  INT                      DEFAULT NULL    COMMENT '该块分词/Token数量, 用于预算上下文长度',
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 联合唯一索引: (文档ID + 块序号) 防止同文档块号重复;
    -- 同时最左前缀覆盖 document_id 查询, 可直接按文档拉取全部块, 无需单独建 idx_document_id
    UNIQUE KEY uk_doc_chunk (document_id, chunk_index)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='文档分块表';

-- ---------------------------------------------------------------------
-- 4. rag_conversation 会话表: 一次连续问答对话的容器
-- ---------------------------------------------------------------------
CREATE TABLE rag_conversation
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id    BIGINT UNSIGNED NOT NULL                COMMENT '归属用户ID, 逻辑关联 sys_user.id',
    title      VARCHAR(255)             DEFAULT NULL    COMMENT '会话标题(通常取首轮问题摘要)',
    deleted    TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除标记: 0未删除 1已删除',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 普通索引: 按用户分页查询会话列表
    KEY idx_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='问答会话表';

-- ---------------------------------------------------------------------
-- 5. rag_message 对话消息表: 存储每一轮 user/assistant 的消息
-- ---------------------------------------------------------------------
CREATE TABLE rag_message
(
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    conversation_id BIGINT UNSIGNED NOT NULL                COMMENT '所属会话ID, 逻辑关联 rag_conversation.id',
    role            VARCHAR(16)     NOT NULL                COMMENT '发言角色: user用户 / assistant助手',
    content         TEXT                     DEFAULT NULL    COMMENT '消息文本内容',
    token_count     INT                      DEFAULT NULL    COMMENT '本条消息消耗的Token数(预留, 用于计费统计)',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 联合索引: (会话ID + 发送时间), 按会话拉取历史时天然按时间正序
    KEY idx_conv_time (conversation_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='对话消息表';
-- （注：内容由AI生成）
