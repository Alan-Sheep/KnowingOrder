-- 索引READY候选指针第一单元，2026-10-20；仅仓储原语，不代表发布服务/HTTP或正式向量问答接通。
-- 先备份并核对目标库；此前任务/清单两份增量必须已核对执行，本表不存在时才执行本脚本。
-- 不自动执行、不重放schema.sql、不修改既有表、不建物理外键；本轮未执行开发MySQL迁移。
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

-- 一、为何这样选与替代方案
-- 一个模型集合内有多个文档的generation；每文档独立发布，不通过切整集合影响其他文档。
-- 复用MySQL事务和比较后更新（CAS），不用内存标志；重启仍可核对真实提交结果，无新依赖。
-- 开工尝试检索并读取Qdrant官方GitHub alias_mapping.rs，Web无正文、网络连接失败；未声称完成源码核验。
-- 未照搬第三方实现；这里是本项目每文档指针，并非Qdrant集合别名的实现。
-- 主键保证每文档一个指针；scope索引供授权定位；job索引供后续保留/清理核对，没有级联删除。

-- 二、代码与数据如何流转
-- KnowledgeVectorPublicationMapper.publish(可信任务快照,事务外已核验的完整摘要行,预期指针修订,时钟)。
-- 上层先重新认证并锁当前MAINTAIN空间，再以同数据源非只读短事务调用；原语本身不是授权写入口。
-- 文档锁串行化首次发布；再锁有效发布版本、VERIFIED任务、完整预期清单和当前片段，最后锁指针。
-- 对比任务全部字段、完整行、片段编号/内容SHA-256，文档修订/原件摘要/有效期必须仍匹配。
-- 例：无指针+预期0→任务A/修订1；切B+预期1→B/修订2；撤回B+预期2→空指针/修订3。
-- 旧的切换B/预期1此时返回409；同一次撤回B/预期2的回执丢失可核对后返回修订3，不再加1。
-- 没有发布过也能撤回：预期0→空指针/修订1，阻止此前预期0的在途首次发布迟到启用。
-- 当前已经为空时，新撤回仍增加修订，屏蔽此前基于该空指针启动的在途发布；同一次重传不增加。
-- publish不改变rag_document.published_version_id，不将任务状态改名READY，不替用户发布业务版本。
-- 草稿索引的文档修订与后来发布不符，必须在业务发布后重新受理构建，本轮不暗改既有生命周期。
-- candidate只返回当前服务端授权、有效业务版本对应的当前任务；任何失效不回退旧代次。
-- candidate返回内部任务快照，不是HTTP DTO，不等于Qdrant可用或最后证据校验通过。

-- 三、问题及解决
-- 不删除撤回行，独立修订递增防止旧请求复活（ABA）；撤回须带预期任务与修订，不能撤掉新代次。
-- 原文/任务损坏、文档已删也允许撤残留指针；写入口仍需上层维护授权，不凭编号操作。
-- VERIFIED不是活任务，不因构建任务deadline过去就不能发布；finished必须在原首次截止前。
-- 发布/撤回上限5秒、SQL超时2秒，异常传播整笔回滚；生产驱动超时/锁等待须MySQL实测。
-- 当前读、参数绑定、禁正文日志；每次candidate复核账号、成员、企业、空间及直接/部门授权。
-- candidate只接受自动提交读取或明确READ_COMMITTED事务，拒绝把MySQL可重复读旧快照当作当前权限。
-- 提交回执不明先按原预期修订核对，不换新请求盲重试；不自动重写外部向量或删除共享集合。
-- 本轮仅仓储，不证明权限写服务、事务外模型/向量复核、HTTP安全响应、前端状态已完成。
-- 2个数据库无法共享本地事务；Qdrant核验与MySQL提交之间仍有间隙，召回时必须再核代次和来源。

-- 四、优化与代价
-- 当前完整清单/片段上限1000；先测事务耗时，再考虑持久封存来源摘要，不能只比count或成功批次。
-- 目前文档任意revision变化都保守失效；可后续拆内容修订与管理修订，需迁移及完整生命周期回归。
-- 后续清理旧代次须核当前指针、在途任务、保留窗口和引用；本单元没有自动删除任务/向量。
-- 待接发布服务后统一同步需求/架构/首次schema；当前正式问答仍用关键词，无MCP/Redis/模型下载。
-- 验证命令（在backend目录，沿用本机JDK/Maven与E盘临时目录配置）：
-- mvn -o -Dtest=KnowledgeVectorPublicationMapperTest test
-- 隔离H2只移除迁移副本中不支持的字符集/排序规则；保留CHECK及真实Mapper，不等于MySQL/Qdrant验收。

-- 五、实际验证与失败记录（2026-10-20）
-- 最终九组541项、失败/错误/跳过0，BUILD SUCCESS；逐份核对9份XML，不是后端全量。
-- 发布仓储67、任务52、清单仓储61、清单内核57、受理61、worker68、模型快照75、Embedding29、Qdrant71。
-- 报告后缀vector-publication-final-20261020；新测试位置KnowledgeVectorPublicationMapperTest.java第47行。
-- 真实事务覆盖首次发布竞争、撤回栅栏、A→B→A旧CAS冲突、调用方回滚和实际commit后回执丢失。
-- 覆盖原件/片段/完整清单不匹配、到期边界、时钟回退、5秒预算、失效身份/权限/部门及RR旧快照拒绝。
-- 故意失败断言未删：业务冲突409、非法输入400、事务/时限503均保留，不以放宽断言换通过。
-- 开发中发现并修复：Java17不支持ExecutorService作为自动关闭资源，改为finally关闭测试线程池；
-- 首轮真实执行62项出现3失败/35错误，发现指针SELECT *列顺序与record顺序不一致，改显式列序；
-- 候选查询的相关子查询JOIN条件未通过H2解析，改为参数关联WHERE计数，保留完整集合与范围校验；
-- 修正后62项通过，补状态/截止/RR边界后67项通过；追加断言遇到泛型重载歧义，用显式结果类型修正。
-- 沙箱下Surefire出现E盘临时目录权限告警；最终经平台许可在原E盘目录重跑，没有改用C盘。
-- 上述只验证H2真实SQL/事务和回环HTTP/方法替身；未执行开发MySQL增量、实际模型/Qdrant或页面验收。
-- 无新增依赖、无模型下载、无付费调用、无Git提交/推送；新增3文件，既有业务生命周期保持兼容。
-- 代码定位：KnowledgeVectorPublicationMapper.java第40行publish、第80行withdraw、第165行candidate。
-- 复验PowerShell（去掉SQL注释前缀后执行）：
-- Set-Location 'D:/Self_Project/Demo/AnsweringSystem/backend'
-- $env:JAVA_HOME='D:/JAVA/jdk-21'
-- $env:PYTHONUTF8='1'
-- $env:TEMP='E:/Codex/home/tmp'
-- $env:TMP='E:/Codex/home/tmp'
-- $env:MAVEN_OPTS='-Djava.io.tmpdir=E:/Codex/home/tmp -Dfile.encoding=UTF-8'
-- & 'C:/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.9/937e263b/bin/mvn.cmd' -s 'C:/Users/Administrator/.m2/settings.xml' -o -B '-DargLine=-Djava.io.tmpdir=E:/Codex/home/tmp -Dfile.encoding=UTF-8' '-Dtest=KnowledgeVectorPublicationMapperTest,KnowledgeVectorWorkerTest,KnowledgeVectorManifestMapperTest,KnowledgeVectorJobMapperTest,KnowledgeVectorJobServiceTest,VectorIndexManifestTest,LocalEmbeddingModelSnapshotTest,LocalEmbeddingClientTest,QdrantVectorClientTest' '-Dsurefire.reportNameSuffix=vector-publication-recheck-20261020' test
-- JDK21执行、release17编译；当前终端未继承PYTHONUTF8=1，以上仅为测试进程设置，不要求反复重启终端。
-- 本单元知识点：RAG索引代次隔离、CAS、撤回墓碑/ABA、数据库行锁/短事务、幂等、回执不明与权限重核。
-- 下一单元：授权发布服务，在事务外复核模型和完整向量清单，再以短事务核权并调用本仓储。
-- 正式向量召回/RRF融合、HTTP/页面状态、旧代次清理和真实MySQL/Qdrant均未因此完成。
