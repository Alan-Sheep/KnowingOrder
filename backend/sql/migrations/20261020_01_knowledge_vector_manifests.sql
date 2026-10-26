-- 完整预期片段清单仓储（2026-10-20），本单元不启动worker、不写Qdrant、不发布READY。
-- 手工迁移前备份；先核对20261019_04两表，再确认本表不存在，部分迁移不得盲目重放。
-- 当前未在开发库执行，不由应用自动执行；首建schema同步和实库验收后续统一处理。

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

-- 一、为什么这样做与替代方案
-- 任务表已有固定企业/空间/文档/版本/代次/模型及manifest_sha256，本表只补逐片段预期摘要。
-- 仅存总摘要无法追踪具体缺失/错内容；JSON大字段更紧凑，但缺少逐项唯一约束和稳定查询定位。
-- 复用MySQL主键、行锁与短事务，不引入Spring Batch/Redis/MQ；原文/向量仍不进入本表。
-- 开工检索Spring Batch官方JdbcExecutionContextDao的执行上下文持久职责，未复制代码或添加依赖。
-- 参考地址：https://github.com/spring-projects/spring-batch/blob/main/spring-batch-core/src/main/java/org/springframework/batch/core/repository/dao/jdbc/JdbcExecutionContextDao.java
-- Web检索未取得可用正文，后用GitHub API查定位并读取固定文件blob：54a0a62149bfcd8843c930f9a13934d7ca94ea56。
-- 核对saveExecutionContext/serializeContext及独立上下文表；blob不是发布版本/提交号。
-- 沙箱网络认证失败后，经平台许可只读核验成功，未下载文件；本表采用关系明细，不照搬其序列化。
-- 主键先job_id后chunk_id，同时支持任务内去重、有序完整读取；任务删除不级联，后续受控清理。

-- 二、代码与数据调用链
-- KnowledgeVectorManifestMapper.persist(id,token,manifest,clock)只允许调用方已开启的同数据源事务。
-- 代码位置：backend/src/main/java/com/ragqa/mapper/KnowledgeVectorManifestMapper.java第23行persist。
-- 先锁活租约任务行，比较可信任务头与内存清单的全部范围/模型/修订/摘要/完整数量。
-- 再核对旧清单；首次一次批量插入1至1000行，读取逐项比较，最终CAS绑定总摘要。
-- 锁顺序：任务行→清单行；READ/CAS均不延长首次任务截止或租约；不更新任务为VERIFIED。
-- 输入例：同一活任务的17个预期点；首次返回1，保持RUNNING且verified_chunks=0；同清单重传返回0。
-- 输入相同任务但变更任何片段内容/向量摘要返回409，旧清单不被覆盖。
-- Mapper的lock/rows/insertRows/seal是内部SQL原语，不提供用户身份或企业权限校验。
-- rows最多读1001项，用额外一项发现超过1000的损坏状态，不将裁剪后的子集视为完整。

-- 三、风险与解决
-- 必须由上层独立短事务包围；异常须向外传播，不能捕获后仍提交半份清单。
-- 无事务/只读事务均返回安全503；本入口不替调用方创建事务，不检查业务权限，不做外部IO。
-- 插入/读取/绑定任一步失败，事务一起回滚；实际提交回执丢失，先核对原UUID，不自动再插。
-- 已绑定清单逐项一致才允许幂等返回；部分清单、不匹配清单拒绝接管，不删除重写。
-- 租约失效、取消、总截止、时钟回退返回冲突；并发同清单先锁任务行，后到者复核持久结果。
-- MySQL原子提交不能覆盖Qdrant；持久清单只表示预期，不证明点已写完、未被篡改或语义正确。
-- 本轮没有worker/HTTP/UI，服务端授权、执行前后复核、页面消息仍由后续执行器/接口负责。
-- 查询使用参数绑定；无用户SQL拼接、原件路径、正文、密钥、token日志或模型调用。
-- 字段仅逻辑关联，无物理外键；限制按任务不超过1000由上层入口和完整清单共同保证。
-- 2秒SQL超时不是强杀线程；真正驱动/锁等待/提交时限及MySQL并发仍须实库验证。

-- 四、优化与代价
-- 先测1000片段单事务耗时/报文与索引体积，再考虑分批暂存加封存状态，不能半份清单提前绑定。
-- 清理按任务终态/保留期有界执行，须与索引发布/失败代次协调；本轮没有自动清理。
-- 如改JSON/压缩，需保留完整性、大小上限、严格解码与版本兼容，并承担失去逐项约束的代价。
-- 持久化用于追溯和后续核对；RUNNING失租仍失败，不借“有清单”擅自自动重放外部写入。
-- 后续：接入授权worker、执行清单与向量写入核对，再事务核权并发布READY，最后接正式混合检索。

-- 五、验证证据与复验（2026-10-20）
-- 首轮新增57项通过；补只读事务/1002项损坏状态/取消竞争/锁等待后，新仓储61项通过。
-- 最终定向231项全部通过：新仓储61、任务仓储52、清单内核57、受理服务61，失败/错误/跳过0。
-- Surefire报告后缀vector-manifest-store-final-20261020，已逐份XML核对；未累计旧全量成绩。
-- 测试位置：backend/src/test/java/com/ragqa/mapper/KnowledgeVectorManifestMapperTest.java。
-- 另有预期抛错用例验证非法输入/表约束/真实SQL封存失败/回滚/实际commit后丢失回执，不删断言换通过。
-- H2仅对迁移副本移除不支持的字符集/排序规则，保留正则CHECK和所有Mapper语句，不改实际迁移。
-- H2不证明MySQL驱动超时、死锁、隔离和生产容量；本轮没有真实MySQL/Qdrant/模型/前端/全量回归。
-- 执行环境JDK21、编译release17；测试使用指定E盘临时目录，经平台许可在沙箱外运行。
-- 当前终端未继承PYTHONUTF8=1，复验只对当前测试进程显式设置，未更改全局环境。
-- 复验PowerShell（去掉每行的SQL注释前缀后执行）：
-- Set-Location 'D:/Self_Project/Demo/AnsweringSystem/backend'
-- $env:JAVA_HOME='D:/JAVA/jdk-21'
-- $env:PYTHONUTF8='1'
-- $env:TEMP='E:/Codex/home/tmp'
-- $env:TMP='E:/Codex/home/tmp'
-- $env:MAVEN_OPTS='-Djava.io.tmpdir=E:/Codex/home/tmp -Dfile.encoding=UTF-8'
-- & 'C:/Users/Administrator/.m2/wrapper/dists/apache-maven-3.9.9/937e263b/bin/mvn.cmd' -s 'C:/Users/Administrator/.m2/settings.xml' -o -B '-DargLine=-Djava.io.tmpdir=E:/Codex/home/tmp -Dfile.encoding=UTF-8' '-Dtest=KnowledgeVectorManifestMapperTest,KnowledgeVectorJobServiceTest,KnowledgeVectorJobMapperTest,VectorIndexManifestTest' '-Dsurefire.reportNameSuffix=vector-manifest-store-recheck-20261020' test
-- 看到231项、失败/错误/跳过0及BUILD SUCCESS为本组成功；这是隔离H2/回环HTTP，不是生产验收。
-- 知识点：完整预期集、摘要绑定、数据库行锁/当前读、短事务原子提交、CAS、幂等及未知提交结果。
-- 未新增依赖、下载模型、执行开发库迁移、提交或推送Git；无MCP/Redis/OCR。
-- 当前：清单内核→任务/模型快照→授权受理→完整清单仓储；下一单元是授权worker。
-- worker/实际向量写入/全量核验/READY发布/正式混合检索尚未接线，不把仓储测试称为向量闭环。
