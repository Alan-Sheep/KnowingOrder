-- 向量索引任务仓储第一单元（2026-10-19），尚未接受理接口、worker或READY发布。
-- 执行前备份开发库并确认USE rag_qa；仅以下两表均不存在时执行，部分存在须先核对定义。
-- 本脚本不由应用自动执行，不重放schema.sql、不修改既有表、不建物理外键。
-- 逻辑关联：enterprise_id/space_id/document_id/version_id/member_id/user_id由后续服务层核权。
-- 为什么单独存任务：Qdrant写入不属于MySQL事务；核对完成VERIFIED不等于可用READY。
-- 为什么不恢复RUNNING：旧worker可能迟到写向量；租约失效后失败，人工新任务使用新generation。
-- 参考Spring Integration官方JdbcLockRegistry的数据库锁/租约职责，复用本项目MyBatis，无新增依赖。

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

-- 索引关联：owner支持本人分页；version支持同版本在途检查；queue/deadline支持有界调度与过期扫描。
-- 容量与同版本互斥必须由后续受理服务在rag_vector_gate行锁内完成，本DDL本身不提供硬配额。
-- 下一单元：授权受理/真实模型修订绑定，执行清单持久化与worker，最后事务核权后READY发布/切换/撤回。
-- 本单元测试：KnowledgeVectorJobMapperTest使用隔离H2执行本脚本，不替代真实MySQL迁移或Qdrant验收。
-- 四方面接续说明（仅仓储，2026-10-19）：
-- 1. 选择MySQL持久任务以复用事务/CAS；纯内存重启即丢，新增MQ不能替代未知写核对，暂不加依赖。
-- 2. insert固定身份/版本/模型/首次截止；claim仅QUEUED进入RUNNING；绑定清单后verified只允许完整数量。
--    例：17片段，绑定清单a×64；传16片段更新0行，传17且活租约才进入VERIFIED，仍无READY发布。
-- 3. generation唯一防代次复用；租约令牌/截止参与每次执行写CAS；取消与完成竞争只允许一个终态。
--    fail仅接受白名单分类，不落上游错误正文；所有运行时SQL参数绑定，单语句2秒查询超时。
--    expire/abandon每次最多更新64条，失租不重放；数据库已提交回执丢失先查，不让迟到失败覆盖。
-- 4. 后续量大再测受理行锁、队列扫描、MySQL死锁/重试与索引；不能从H2两线程推断生产吞吐。
--    孤立Qdrant点需后续按失败代次授权清理，不能随意删集合，也不能把本地回滚称为外部回滚。
-- 首轮51项因H2不支持MySQL可空字段字符集语法，在DDL阶段错误；仅测试副本移除字符集/排序规则后通过。
-- 随后Surefire出现E盘临时目录权限警告，经许可沙箱外原断言重跑，并补提交后回执丢失反例。
-- 最终定向180项通过：新仓储52、清单57、客户端71；失败/错误/跳过0，未重跑全量/前端/实库/真实模型。
-- Surefire报告后缀：vector-job-store-final-20261019；失败报告保留vector-job-store-20261019。
-- 复验：backend目录，既有JDK21/Maven离线，临时目录E:/Codex/home/tmp；
-- mvn -o "-DargLine=-Djava.io.tmpdir=E:/Codex/home/tmp -Dfile.encoding=UTF-8" "-Dtest=KnowledgeVectorJobMapperTest,VectorIndexManifestTest,QdrantVectorClientTest" test
-- 当前没有在开发库执行此迁移，也未同步首建schema.sql；后续接线/首建一致性核验后统一实库验收，暂不要求用户执行。
-- 已有清单仍在内存，本表只预留清单摘要，不等于完整清单已持久化；真实模型修订来源、受理幂等与权限隔离由后续服务落实。
-- 本轮无MCP/Redis/OCR，无密钥读写、模型下载、Git提交或推送。
