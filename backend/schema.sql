-- =====================================================================
-- RAG 智能问答系统 · 库表结构(MySQL 8.0.16及以上 / InnoDB / utf8mb4)
-- 说明: 本脚本仅供空库初始化; 已有库使用 sql/migrations 下的独立增量脚本。
--       不建物理外键, 表间通过索引字段逻辑关联; CHECK约束须由目标数据库强制执行。
-- 关联链路:
--   sys_user 1 -- N rag_document      (created_by -> sys_user.id)
--   sys_user 1 -- N rag_conversation   (user_id   -> sys_user.id)
--   rag_guest_identity 1 -- N rag_conversation (guest_id -> rag_guest_identity.id)
--   rag_document 1 -- N rag_chunk       (document_id -> rag_document.id)
--   rag_document 1 -- N rag_document_version (document_id -> rag_document.id)
--   rag_document_version 1 -- N rag_chunk (version_id -> rag_document_version.id, 历史片段为0)
--   rag_conversation 1 -- N rag_message (conversation_id -> rag_conversation.id)
-- =====================================================================
-- 一、表作用
--   1. sys_user         系统用户: 账号、密码散列、状态。问答系统与外部的身份锚点
--   2. rag_document     知识库文档: 原始文档的元信息与解析状态, 检索结果的溯源起点
--   3. rag_chunk        文档分块: 文档切分后的文本片段, 每个片段是检索的最小命中单位
--   4. rag_conversation 问答会话: 一次连续对话的容器, 决定多轮上下文的边界
--   5. rag_message      对话消息: 每轮 user/assistant 消息, 会话的历史记录
--   6. rag_guest_identity 访客临时身份: 凭据摘要、生命周期状态及分开的受理/执行/读取期限
--   7. rag_document_version 文档版本: 私有原件标识、幂等指纹、解析/发布状态及有效期
--
--   向量(embedding)不进 rag_chunk, 由专用向量库存储, 本表只留文本与统计字段。
--
-- 二、关联字段(逻辑关联, 均为 BIGINT UNSIGNED 且不建物理外键)
--   rag_document.created_by      -> sys_user.id          文档归属者
--   rag_conversation.user_id     -> sys_user.id          账号归属, 与guest_id恰好一个非空
--   rag_conversation.guest_id    -> rag_guest_identity.id 访客归属, 与user_id恰好一个非空
--   rag_chunk.document_id        -> rag_document.id      块所属文档
--   rag_chunk.version_id         -> rag_document_version.id 块所属版本，历史值0不自动补造版本
--   rag_document.owner_member_id -> sys_enterprise_member.id 文档负责人，不等于账号编号
--   rag_document.published_version_id -> rag_document_version.id 当前发布指针，服务层检查同企业/空间/文档
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
--     uk_doc_chunk(document_id, version_id, chunk_index) 唯一+联合
--       保证同版本块序号不重复，新旧版本不会覆盖；按文档/版本过滤后按chunk_index读取。
--   rag_document_version
--     uk_version_no(document_id,version_no) 保证文档内版本序号唯一
--     uk_upload_request(enterprise_id,knowledge_space_id,upload_request_id) 防同范围重复受理；参数指纹仍由服务核对
--     idx_version_scope(enterprise_id,knowledge_space_id,document_id,id) 支持带范围版本定位
--   rag_conversation
--     idx_user_id           普通  会话列表按用户分页
--     idx_guest_id          普通  按访客查询会话及定位待清理数据
--   rag_guest_identity
--     uk_guest_credential_hash(credential_hash) 唯一  按凭据摘要定位身份并防重复
--     idx_guest_expiry(expires_at_epoch_ms)     普通  按到期时刻分批扫描; 不会自动清理
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
--             访客期限使用UTC毫秒; accept_until为北京时间次日零点, 停止接收新问题。
--             drain_until为固定执行上限, expires_at为身份最迟失效上限, 读取不续期。
--             单任务结果期限取结束时刻加阅读窗口与身份硬上限的较早者, 由业务固定保存。
--   访客状态: 0正常 / 1撤销 / 2收尾 / 3到期; 即使状态滞后, 也须按时间和任务范围拒绝越界。
--   阅读窗口: 新身份初值600000毫秒(10分钟); 迁移旧记录为0, 不自动补发收尾权限。
--   访客凭据: 只存SHA-256摘要, 不存原始凭据; 摘要也不得返回客户端或记日志。
--   归属约束: CHECK只保证账号/访客二选一, 身份存在、撤销、到期仍须service校验。
--   计数列:   rag_document.chunk_count 是冗余统计, 需在分块写入后同步更新,
--             新入库版本在rag_document_version维护各自片段数；发布时文档汇总当前发布版片段数。
--             不要把所有历史版本的SELECT COUNT(*)当作当前发布版计数。
--   字符集:   全库 utf8mb4 / utf8mb4_unicode_ci, 与 mysqldump 及应用连接串保持一致。
--
-- 五、与后端实体的对应
--   com.ragqa.domain.entity.SysUser / RagDocument / RagChunk / RagConversation / RagMessage
--   rag_guest_identity及guest_id对应的Java实体/归属查询尚待后续单元接入。
--   既有业务枚举同步自 com.ragqa.common.Constants; 访客生命周期枚举尚待后续Java单元接入。
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
    auth_version  BIGINT          NOT NULL DEFAULT 1      COMMENT '认证版本，改密或撤销会话时递增',
    deleted       TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除标记: 0未删除 1已删除',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 唯一索引: 保证登录名不重复, 登录查询直接命中
    UNIQUE KEY uk_username (username),
    -- 普通索引: 支持按邮箱检索/找回账号
    KEY idx_email (email),
    CONSTRAINT chk_user_auth_version CHECK (auth_version > 0)
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
    enterprise_id BIGINT UNSIGNED DEFAULT NULL COMMENT '企业，旧记录保持空',
    knowledge_space_id BIGINT UNSIGNED DEFAULT NULL COMMENT '知识空间，逻辑关联rag_knowledge_space.id',
    owner_member_id BIGINT UNSIGNED DEFAULT NULL COMMENT '知识负责人；旧记录为空，不自动继承企业授权',
    published_version_id BIGINT UNSIGNED DEFAULT NULL COMMENT '当前发布指针，逻辑关联rag_document_version.id',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '文档变更版本，防止旧页面覆盖新修改',
    deleted     TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除标记: 0未删除 1已删除',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 普通索引: 按解析状态筛选待处理队列(定时任务扫描 0/3 状态)
    KEY idx_status (status),
    -- 普通索引: 按上传人查询自己的文档列表
    KEY idx_created_by (created_by),
    KEY idx_document_space (enterprise_id, knowledge_space_id, id),
    CONSTRAINT chk_document_enterprise CHECK (
        (enterprise_id IS NULL AND knowledge_space_id IS NULL)
        OR (enterprise_id IS NOT NULL AND knowledge_space_id IS NOT NULL)),
    CONSTRAINT chk_document_revision CHECK (revision > 0)
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
    version_id   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '文档版本，0仅兼容历史片段，不参与新版检索',
    chunk_index  INT             NOT NULL                COMMENT '块在原文档中的序号(从0开始)',
    content      MEDIUMTEXT               DEFAULT NULL    COMMENT '分块纯文本内容',
    token_count  INT                      DEFAULT NULL    COMMENT '该块分词/Token数量, 用于预算上下文长度',
    start_offset INT DEFAULT NULL COMMENT '原文UTF-16起始位置，含该位置',
    end_offset INT DEFAULT NULL COMMENT '原文UTF-16结束位置，不含该位置',
    start_line INT DEFAULT NULL COMMENT '原文起始行，从1开始',
    end_line INT DEFAULT NULL COMMENT '原文结束行，从1开始',
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 联合唯一索引: (文档ID + 块序号) 防止同文档块号重复;
    -- 同时最左前缀覆盖 document_id 查询, 可直接按文档拉取全部块, 无需单独建 idx_document_id
    UNIQUE KEY uk_doc_chunk (document_id, version_id, chunk_index)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='文档分块表';

-- ---------------------------------------------------------------------
-- 4. rag_conversation 会话表: 一次连续问答对话的容器
-- ---------------------------------------------------------------------
CREATE TABLE rag_conversation
(
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id    BIGINT UNSIGNED          DEFAULT NULL    COMMENT '账号归属, 与guest_id二选一',
    guest_id   BIGINT UNSIGNED          DEFAULT NULL    COMMENT '访客归属, 逻辑关联rag_guest_identity.id',
    enterprise_id BIGINT UNSIGNED DEFAULT NULL COMMENT '企业，旧记录保持空',
    member_id BIGINT UNSIGNED DEFAULT NULL COMMENT '所属成员，逻辑关联sys_enterprise_member.id',
    title      VARCHAR(255)             DEFAULT NULL    COMMENT '会话标题(通常取首轮问题摘要)',
    deleted    TINYINT         NOT NULL DEFAULT 0      COMMENT '逻辑删除标记: 0未删除 1已删除',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 普通索引: 按用户分页查询会话列表
    KEY idx_user_id (user_id),
    -- 普通索引: 按访客查询会话及定位待清理数据
    KEY idx_guest_id (guest_id),
    KEY idx_conversation_scope (enterprise_id, member_id, user_id, id),
    CONSTRAINT chk_conversation_enterprise CHECK (
        (enterprise_id IS NULL AND member_id IS NULL)
        OR (enterprise_id IS NOT NULL AND member_id IS NOT NULL AND user_id IS NOT NULL AND guest_id IS NULL)),
    CONSTRAINT chk_conversation_owner CHECK (
        (user_id IS NOT NULL AND guest_id IS NULL)
        OR (user_id IS NULL AND guest_id IS NOT NULL))
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
-- ---------------------------------------------------------------------
-- 6. rag_guest_identity 访客临时身份表: 与正式账号独立保存
--    无物理外键, 创建顺序不依赖会话表; 到期拒绝访问与物理清理均待业务实现
-- ---------------------------------------------------------------------
CREATE TABLE rag_guest_identity
(
    id                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '访客内部主键, 不作凭据',
    credential_hash        BINARY(32)      NOT NULL COMMENT '凭据SHA-256摘要, 禁止返回或记日志',
    issued_at_epoch_ms     BIGINT          NOT NULL COMMENT '签发时刻, UTC毫秒',
    accept_until_epoch_ms  BIGINT          NOT NULL COMMENT '新问题受理截止, UTC毫秒, 北京时间次日零点',
    drain_until_epoch_ms   BIGINT          NOT NULL COMMENT '已受理任务执行硬上限, UTC毫秒, 不延长原任务时限',
    expires_at_epoch_ms    BIGINT          NOT NULL COMMENT '身份最迟失效硬上限, UTC毫秒, 不代表通用读取权限',
    status                 TINYINT         NOT NULL DEFAULT 0 COMMENT '生命周期: 0正常 1撤销 2收尾 3到期',
    result_read_window_ms  BIGINT          NOT NULL DEFAULT 600000 COMMENT '结果阅读窗口毫秒, 新身份初值10分钟, 0仅用于兼容旧记录',
    PRIMARY KEY (id),
    UNIQUE KEY uk_guest_credential_hash (credential_hash),
    KEY idx_guest_expiry (expires_at_epoch_ms),
    CONSTRAINT chk_guest_time_bounds CHECK (
        issued_at_epoch_ms >= 0
        AND issued_at_epoch_ms < accept_until_epoch_ms
        AND accept_until_epoch_ms <= drain_until_epoch_ms
        AND drain_until_epoch_ms <= expires_at_epoch_ms),
    CONSTRAINT chk_guest_status CHECK (status IN (0, 1, 2, 3)),
    CONSTRAINT chk_guest_read_window CHECK (result_read_window_ms >= 0)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='当日访客临时身份';
-- 六、企业授权表：企业->成员/直属部门；企业->知识空间->明确授权->文档。
-- 联合索引的企业前缀用于缩小扫描范围，不代替SQL中的企业一致性校验。
-- uk_enterprise_user防重复成员；授权的两个唯一索引分别限制成员/部门重复授权。
-- idx_task_conversation关联任务、会话、消息；此处只准备归属锚点，不实现执行器。
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

-- 文档版本独立于发布指针：新版本入库失败不能覆盖仍合法有效的已发布版本。
-- 先插rag_document再插版本，再按版本插rag_chunk；均由服务事务保证逻辑关联，无物理外键。
-- uk_version_no保证单文档版本号；uk_upload_request限定同企业空间的上传幂等请求。
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

-- 以下三表供首次建库；已有库只执行20261019_01_knowledge_workspace.sql，不重放本文件。
-- 会话归属限定企业/成员/账号/空间；任务编号用于幂等；反馈逻辑关联任务保留的原始引用版本。
CREATE TABLE rag_knowledge_chat (
    id CHAR(36) NOT NULL COMMENT '客户端生成的会话UUID，重连复用',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '企业逻辑关联',
    member_id BIGINT UNSIGNED NOT NULL COMMENT '归属成员，通讯录可见不等于可读',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '归属账号',
    space_id BIGINT UNSIGNED NOT NULL COMMENT '本会话固定知识空间',
    title VARCHAR(80) NOT NULL COMMENT '首问标题',
    created_ms BIGINT NOT NULL COMMENT '服务端UTC创建毫秒',
    PRIMARY KEY(id),
    KEY idx_chat_owner(enterprise_id,member_id,space_id,created_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='企业私有问答会话';

CREATE TABLE rag_knowledge_job (
    id CHAR(36) NOT NULL COMMENT '客户端请求UUID，重复提交必须复用',
    chat_id CHAR(36) NOT NULL COMMENT '所属会话逻辑关联',
    auth_version BIGINT NOT NULL COMMENT '受理时账号认证版本，撤销后不升级',
    question VARCHAR(1200) NOT NULL COMMENT '用户原问题，禁止日志输出',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING/RUNNING/COMPLETED/FAILED/CANCELLED/TIMED_OUT',
    created_ms BIGINT NOT NULL COMMENT '首次受理UTC毫秒',
    deadline_ms BIGINT NOT NULL COMMENT '首次受理后30秒硬截止，重连不续期',
    finished_ms BIGINT DEFAULT NULL COMMENT '终态时刻',
    answer_json LONGTEXT COMMENT '完整回答快照，每次读取重新核权',
    error_code INT DEFAULT NULL COMMENT '安全错误码，不存第三方异常正文',
    PRIMARY KEY(id),
    KEY idx_job_chat(chat_id,created_ms,id),
    KEY idx_job_dispatch(status,deadline_ms,created_ms),
    CONSTRAINT chk_job_status CHECK(status IN ('PENDING','RUNNING','COMPLETED','FAILED','CANCELLED','TIMED_OUT')),
    CONSTRAINT chk_job_deadline CHECK(deadline_ms > created_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='有界问答任务，不自动重放运行中任务';

CREATE TABLE rag_knowledge_feedback (
    id CHAR(36) NOT NULL COMMENT '反馈请求UUID，重试复用',
    job_id CHAR(36) NOT NULL COMMENT '本人回答任务逻辑关联',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '企业范围',
    space_id BIGINT UNSIGNED NOT NULL COMMENT '知识空间范围',
    member_id BIGINT UNSIGNED NOT NULL COMMENT '反馈人',
    chunk_id BIGINT UNSIGNED DEFAULT NULL COMMENT '选中的引用片段，原版本保存在任务快照',
    category VARCHAR(20) NOT NULL COMMENT 'INCORRECT/MISSING/OUTDATED/OTHER',
    comment_text VARCHAR(1000) NOT NULL COMMENT '员工说明，纯文本展示',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED/DISMISSED',
    resolution VARCHAR(1000) DEFAULT NULL COMMENT '维护者处理说明，不自动改制度',
    resolved_by BIGINT UNSIGNED DEFAULT NULL COMMENT '处理成员',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '并发处理乐观版本',
    created_ms BIGINT NOT NULL COMMENT '提交UTC毫秒',
    PRIMARY KEY(id),
    KEY idx_feedback_space(enterprise_id,space_id,status,created_ms),
    CONSTRAINT chk_feedback_status CHECK(status IN ('OPEN','RESOLVED','DISMISSED')),
    CONSTRAINT chk_feedback_revision CHECK(revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='可追溯知识反馈与人工处理';

-- 首建使用；已有库执行20261019_02_knowledge_context.sql。仅逻辑关联job_id，不建物理外键。
-- PRIMARY KEY同时保证一任务一快照并支持按任务点查，无需重复创建job_id索引。
CREATE TABLE rag_knowledge_context (
    job_id CHAR(36) NOT NULL COMMENT '受理任务UUID，逻辑关联任务且每任务唯一',
    context_json TEXT NOT NULL COMMENT '受理时当前及最多4条历史原问题快照，禁止日志输出',
    snapshot_id CHAR(64) NOT NULL COMMENT '完整输入SHA-256，不作权限凭据',
    PRIMARY KEY(job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='私有追问受理快照，不自动更新';

-- 首建入库队列；已有库执行20261019_03。gate行仅串行化短受理事务，绝不跨文件解析持锁。
CREATE TABLE rag_ingest_gate (
    id INT NOT NULL COMMENT '唯一受理锁行，不保存业务内容',
    PRIMARY KEY(id),
    CONSTRAINT chk_ingest_gate CHECK(id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='入库队列容量的跨节点受理锁';

INSERT INTO rag_ingest_gate(id) VALUES(1);

CREATE TABLE rag_ingest_job (
    id CHAR(36) NOT NULL COMMENT '受理UUID，重传保持相同编号',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '企业逻辑关联',
    space_id BIGINT UNSIGNED NOT NULL COMMENT '知识空间逻辑关联',
    document_id BIGINT UNSIGNED NOT NULL COMMENT '逻辑文档编号',
    version_id BIGINT UNSIGNED NOT NULL COMMENT '原件版本编号',
    member_id BIGINT UNSIGNED NOT NULL COMMENT '提交成员，读取任务仍须维护权限',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '账号编号',
    auth_version BIGINT NOT NULL COMMENT '受理认证版本，后台复核',
    expected_revision BIGINT NOT NULL COMMENT '文档预期修订，不自动覆盖并发修改',
    action VARCHAR(16) NOT NULL COMMENT 'PARSE幂等解析，REBUILD仅草稿重建',
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/TIMED_OUT',
    attempts INT NOT NULL DEFAULT 0 COMMENT '执行次数，最多两次，仅租约失效可自动恢复',
    lease_token CHAR(36) DEFAULT NULL COMMENT '执行栅栏令牌，不返回前端',
    lease_until_ms BIGINT DEFAULT NULL COMMENT '15秒租约，不延长原总截止',
    created_ms BIGINT NOT NULL COMMENT '受理UTC毫秒',
    deadline_ms BIGINT NOT NULL COMMENT '受理起30秒总截止',
    finished_ms BIGINT DEFAULT NULL COMMENT '终态UTC毫秒',
    failure_code VARCHAR(32) DEFAULT NULL COMMENT '白名单安全分类，不保存异常正文',
    PRIMARY KEY(id),
    KEY idx_ingest_owner(enterprise_id,space_id,member_id,created_ms,id),
    KEY idx_ingest_version(document_id,version_id,status),
    KEY idx_ingest_queue(status,created_ms),
    KEY idx_ingest_deadline(status,deadline_ms),
    CONSTRAINT chk_ingest_action CHECK(action IN ('PARSE','REBUILD')),
    CONSTRAINT chk_ingest_status CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','TIMED_OUT')),
    CONSTRAINT chk_ingest_attempts CHECK(attempts BETWEEN 0 AND 2),
    CONSTRAINT chk_ingest_revision CHECK(expected_revision>0 AND auth_version>0),
    CONSTRAINT chk_ingest_time CHECK(created_ms>=0 AND deadline_ms>created_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='私有文字入库持久任务，片段与成功终态同事务';

-- 首建向量索引控制面（2026-10-24同步）；已有库依次核对以下三份增量，不重放本文件：
-- 20261019_04_knowledge_vector_jobs.sql → 20261020_01_knowledge_vector_manifests.sql
-- → 20261020_02_knowledge_vector_publication.sql。下列四表定义与对应增量保持逐字一致。
-- 创建顺序：gate及唯一锁行 → job任务 → manifest完整预期明细 → publication每文档指针。
-- 逻辑关联：job固定企业/空间/文档/版本/成员/账号；manifest.job_id和publication.job_id关联job.id。
-- manifest.chunk_id关联rag_chunk.id；publication.document_id关联rag_document.id，均不建物理外键。
-- 关系完整性、授权、队列容量、核验及发布由服务事务保证；DDL不会把VERIFIED自动变成READY。
CREATE TABLE rag_vector_gate (
    id INT NOT NULL COMMENT '唯一受理锁行，后续容量检查与插入须在同一事务',
    PRIMARY KEY(id),
    CONSTRAINT chk_vector_gate CHECK(id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='向量索引队列受理锁';

INSERT INTO rag_vector_gate(id) VALUES(1);

CREATE TABLE rag_vector_job (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求UUID，重传不换编号',
    generation CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端新代次UUID，禁止重用失败任务代次',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '逻辑关联sys_enterprise.id',
    space_id BIGINT UNSIGNED NOT NULL COMMENT '逻辑关联rag_knowledge_space.id',
    document_id BIGINT UNSIGNED NOT NULL COMMENT '逻辑关联rag_document.id',
    version_id BIGINT UNSIGNED NOT NULL COMMENT '逻辑关联rag_document_version.id',
    member_id BIGINT UNSIGNED NOT NULL COMMENT '受理成员，任务读取仍须本人及维护授权',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '受理账号，不与访客共用身份',
    auth_version BIGINT NOT NULL COMMENT '受理认证版本，后台仍须重新核权',
    expected_revision BIGINT NOT NULL COMMENT '受理时文档修订，禁止覆盖后续修改',
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范受理参数指纹，同UUID换参冲突由服务层判断',
    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定版本原件摘要，不保存正文',
    collection_name VARCHAR(83) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Qdrant集合名',
    model_name VARCHAR(128) NOT NULL COMMENT 'Embedding模型标识，不含凭据',
    model_revision CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '真实权重修订摘要，后续服务解析来源',
    dimensions INT NOT NULL COMMENT '向量维度，1至2048',
    expected_chunks INT NOT NULL COMMENT '可信版本完整片段数，1至1000，不由成功批次数反算',
    status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/VERIFIED/FAILED/CANCELLED/TIMED_OUT，无READY发布状态',
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '执行栅栏，内部使用，不返回前端',
    lease_until_ms BIGINT DEFAULT NULL COMMENT '租约截止，不得超过任务首次总截止',
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '执行期一次性绑定的完整预期清单摘要',
    verified_chunks INT NOT NULL DEFAULT 0 COMMENT '全量核对成功前恒为0，不能逐批累加冒充完整',
    created_ms BIGINT NOT NULL COMMENT '首次受理UTC毫秒',
    deadline_ms BIGINT NOT NULL COMMENT '首次任务硬截止，claim不延长',
    finished_ms BIGINT DEFAULT NULL COMMENT '终态UTC毫秒',
    failure_code VARCHAR(32) DEFAULT NULL COMMENT '安全失败分类，不保存上游正文',
    PRIMARY KEY(id),
    UNIQUE KEY uk_vector_generation(generation),
    KEY idx_vector_owner(enterprise_id,space_id,member_id,created_ms,id),
    KEY idx_vector_version(enterprise_id,space_id,document_id,version_id,status),
    KEY idx_vector_queue(status,created_ms,id),
    KEY idx_vector_deadline(status,deadline_ms),
    CONSTRAINT chk_vector_scope CHECK(enterprise_id>0 AND space_id>0 AND document_id>0 AND version_id>0 AND member_id>0 AND user_id>0),
    CONSTRAINT chk_vector_revision CHECK(auth_version>0 AND expected_revision>0),
    CONSTRAINT chk_vector_size CHECK(dimensions BETWEEN 1 AND 2048 AND expected_chunks BETWEEN 1 AND 1000),
    CONSTRAINT chk_vector_status CHECK(status IN ('QUEUED','RUNNING','VERIFIED','FAILED','CANCELLED','TIMED_OUT')),
    CONSTRAINT chk_vector_time CHECK(created_ms>=0 AND deadline_ms>created_ms AND (finished_ms IS NULL OR finished_ms>=created_ms)),
    CONSTRAINT chk_vector_lease CHECK((lease_token IS NULL AND lease_until_ms IS NULL) OR
        (lease_token IS NOT NULL AND lease_until_ms IS NOT NULL AND lease_until_ms>created_ms AND lease_until_ms<=deadline_ms)),
    CONSTRAINT chk_vector_running CHECK(status<>'RUNNING' OR lease_token IS NOT NULL),
    CONSTRAINT chk_vector_terminal CHECK((status IN ('QUEUED','RUNNING') AND finished_ms IS NULL) OR
        (status IN ('VERIFIED','FAILED','CANCELLED','TIMED_OUT') AND finished_ms IS NOT NULL)),
    CONSTRAINT chk_vector_verified CHECK((status='VERIFIED' AND manifest_sha256 IS NOT NULL AND verified_chunks=expected_chunks AND failure_code IS NULL)
        OR (status<>'VERIFIED' AND verified_chunks=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='向量索引私有持久任务，核对成功不等于发布';

CREATE TABLE rag_vector_manifest_entry (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '逻辑关联rag_vector_job.id，身份/模型/版本头保存在任务表',
    chunk_id BIGINT UNSIGNED NOT NULL COMMENT '逻辑关联rag_chunk.id，完整预期片段，不以成功写入子集反算',
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定片段内容摘要，不保存原文',
    vector_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '预期原始向量摘要，不保存向量数值',
    PRIMARY KEY(job_id,chunk_id),
    CONSTRAINT chk_vector_entry_chunk CHECK(chunk_id>0),
    CONSTRAINT chk_vector_entry_job CHECK(REGEXP_LIKE(job_id,'^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$','c')),
    CONSTRAINT chk_vector_entry_content CHECK(REGEXP_LIKE(content_sha256,'^[0-9a-f]{64}$','c')),
    CONSTRAINT chk_vector_entry_vector CHECK(REGEXP_LIKE(vector_sha256,'^[0-9a-f]{64}$','c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='索引完整预期清单，不是已写入结果或可用索引';

CREATE TABLE rag_vector_publication (
    document_id BIGINT UNSIGNED NOT NULL COMMENT '逻辑关联rag_document.id，每逻辑文档只有一个当前指针',
    enterprise_id BIGINT UNSIGNED NOT NULL COMMENT '逻辑关联sys_enterprise.id，防跨企业错连',
    space_id BIGINT UNSIGNED NOT NULL COMMENT '逻辑关联rag_knowledge_space.id，防跨空间错连',
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '逻辑关联已核对任务，空值是撤回墓碑',
    revision BIGINT NOT NULL COMMENT '独立发布指针CAS修订，从1递增，不是文档修订或任务代次',
    updated_ms BIGINT NOT NULL COMMENT '服务端变更UTC毫秒，不是排序或幂等凭据',
    PRIMARY KEY(document_id),
    KEY idx_vector_publication_scope(enterprise_id,space_id,document_id),
    KEY idx_vector_publication_job(job_id),
    CONSTRAINT chk_vector_publication_scope CHECK(document_id>0 AND enterprise_id>0 AND space_id>0),
    CONSTRAINT chk_vector_publication_revision CHECK(revision>0),
    CONSTRAINT chk_vector_publication_time CHECK(updated_ms>=0),
    CONSTRAINT chk_vector_publication_job CHECK(job_id IS NULL OR REGEXP_LIKE(job_id,'^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$','c'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='索引当前代次指针，撤回不删除墓碑';

-- 索引用途：gate主键锁支持受理短事务；generation唯一索引阻止代次复用；owner定位本人任务；
-- version检查同范围同版本在途任务；queue/deadline用于有界调度/过期扫描；manifest复合主键防片段重复；
-- publication主键保证每文档一个指针，scope用于范围定位，job用于后续保留/清理核对，不做级联删除。
