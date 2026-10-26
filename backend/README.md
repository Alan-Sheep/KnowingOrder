# 知序 · 企业知识与工作助手后端

## 最新：持久会话、任务和反馈（2026-10-19）

新增`/api/enterprises/{enterpriseId}/knowledge-spaces/{spaceId}/workspace`：读取本人会话/历史、受理与查询任务、取消、提交反馈及维护者处理。沿用Session和新CSRF，所有入口禁记请求/响应正文。任务首次受理后30秒，每成员最多两条在途、本节点两执行槽；重复UUID不延时，取消/超时不能被迟到结果覆盖。PENDING重启可恢复，RUNNING不自动重放以避免重复计费。历史引用每次重新核权；不会因持久保存绕过撤回。后台任务UUID贯穿request_id，Mapper日志限制INFO，不输出私有SQL参数。

已有开发库需先备份、核对三张新表，再执行`sql/migrations/20261019_01_knowledge_workspace.sql`；禁止重放schema.sql或盲目重跑部分成功的DDL。本机首次验收确认缺rag_knowledge_chat，用户补齐三表后真实MySQL＋页面18/18通过，无模型/搜索调用；不代表其他环境已迁移或全部DDL已审计。步骤、接口、文件清单、故障记录及四方面面试说明见[本轮实施记录](../docs/企业会话任务反馈与本机向量适配实施记录-2026-10-19.md)。缺表不自动迁移，页面明确历史不可用，旧同步回答保持兼容。

新增可选环境变量`KNOWLEDGE_EMBEDDING_ENABLED`（默认false）、`KNOWLEDGE_EMBEDDING_BASE_URL`（默认http://127.0.0.1:11434）、`KNOWLEDGE_EMBEDDING_MODEL`（默认空）。仅本机Ollama协议适配，不下载模型、不自动启用、不接正式召回；当前无需填写。真实企业资料仍不外发。模型准备完再单独验证维度/索引/效果，不能把开关打开等同向量检索完成。

本轮全量verify1458项通过；后续安全追踪与入口检查补丁相关86项复验通过；前端44项、企业拦截24项、原页面18项及构建通过。后续新功能真实MySQL＋页面18/18通过，测试空间16合成版本已撤回；本轮没有真实模型/Embedding或生产容量成绩，下方记录按日期保留。

## 最新：企业授权检索与回答（2026-10-18）

已接通MySQL关键词召回、当前发布/生效版本过滤、原文溯源及Vue问答；不是向量检索。默认`LOCAL_EVIDENCE`只回原文，不发送问题和原文给模型。仅服务端`KNOWLEDGE_SYNTHETIC_MODEL_SPACES`显式许可的合成空间可`SYNTHETIC_MODEL`，一次模型最多三问、引用与完整覆盖校验，无自动重试或公开搜索。当前本机许可`1:12`仅供虚构资料，禁止真实企业材料混入；默认值空。模型仍沿用现有环境变量，不新增凭据。

接口基址`/api/enterprises/{enterpriseId}/knowledge-spaces/{spaceId}/answers`：GET `/mode`先展示边界，POST传`{"question":"餐补上限多少？谁审批？多久到账？"}`（1—1200字符，Session＋CSRF），GET `/evidence/{chunkId}`重新授权引用。返回Result，编号为Long字符串；PARTIAL/NO_EVIDENCE/MODEL_UNAVAILABLE明确告知缺口或失败。维护者也只用发布版回答。后台两执行者零排队，第三条503，整体30秒；取消等待不能保证上游停止计费。

后端本阶段verify1018项、失败/错误/跳过0；真实MySQL＋当前模型页面15/15，三问30元/直属主管审批有引用、到账时间不足，23674ms只是单样本。企业1/空间12本次合成原件最终撤回，无公开搜索。完整24文件、四方面/知识点、测试故障及复验见[企业问答闭环记录](../docs/企业授权检索与回答最小闭环记录-2026-10-18.md)。未新增依赖或迁移。下一步企业合成黄金集；向量、持久会话/任务、重连、多角色完整实库和生产容量仍未验收。本节优先于下方历史能力描述。

## 当前目标与能力边界（2026-10-17）

**2026-10-18较早的入库实现**：空间列表/维护/明确授权、TXT/Markdown私有上传、原件下载、负责人/版本/有效期、发布/撤回/逻辑删除、解析切分与片段入库已经接入Vue。当时后端972项、前端31项与拦截13＋18项通过。用户执行新迁移并提供三表定义核对，IDEA入库IT整类退出码0（无逐项汇总，AI未重跑）；当时真实文档页面13/13通过，未调用模型/搜索。这是[入库阶段历史证据](../docs/知识管理与文字入库闭环实施记录-2026-10-18.md)，后续回答状态以上方最新节为准；完整企业权限和生产容量仍待验收。

已有开发库先备份、核对，逐段执行`sql/migrations/20261018_01_knowledge_ingest.sql`（前提已完成20261017_03），禁止重放`schema.sql`。新增表`rag_document_version`，文档增加负责人/指针/revision，片段唯一键增加版本维度；无物理外键，不自动回填旧资料。详见入库记录第8节。

原件默认`E:/Codex/data/knowingorder/knowledge`，临时上传默认`E:/Codex/home/tmp`；可用`KNOWLEDGE_STORAGE_ROOT`、`KNOWLEDGE_UPLOAD_TEMP`覆盖，生产必须指定正确私有目录并验权限/备份/容量，不得静态公开。单原件1MiB、multipart2MiB，首批只收UTF-8 TXT/MD，不发给模型/OCR。JSON用Result，原件为授权二进制附件。

新实库验收类`KnowledgeIngestMysqlIT`仅显式运行：IDEA JUnit配置`DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，工作目录backend、VM选项`-Djava.io.tmpdir=E:/Codex/home/tmp`。DB_URL仅允许本机显式端口；测试独立随机企业，最后核对标记精确清理，不改已有企业或执行DDL。用户已运行含三个方法的整类，退出码0，无逐项汇总；AI未独立运行，不要求把密码发给AI。

主业务是企业知识库与业务问答，员工事务及补贴为辅助。当前产品范围见 [需求分析](../02-需求分析.md)、[目标架构](../03-架构设计.md) 及 [当前待办](../docs/开发待办.md)，实施采用 [04指南](../04-实施指南.md) 的A—H路线。

- 已有Java基础设施和联网问答继续复用；新增Spring Security账号密码登录、企业成员校验及资源授权入口。`CurrentUserService`仍只负责账号复核，不能单独提供企业授权。
- `POST /api/chat`仍是仅dev本机的同步公开体验；企业登录和授权读取已实现，但目标MySQL迁移、前端登录未验收。入库/检索、持久任务/SSE、真实渠道与补贴结算未完成；Mock商品不代表真实接入。
- 原访客路线冻结保留；不自动执行访客SQL，不按访客规则删除企业成员数据。
- 辅助线只查询推荐与跳转，用户在外部平台下单支付；无OCR。渠道状态未核验时不能自动认定补贴资格。
- 现有第三方中转和搜索的连通记录不授权外发企业资料，企业数据接入前需核对模型和存储许可。
- `com.ragqa`、`rag_qa`、`rag-qa`等继续作为工程兼容标识，本轮不改包名、库名、日志名或构建配置。下方命令保留用于现有工程，不表示生产已验收。

基于 Spring Boot 的后端服务。当前开发进度与验收边界见 [开发待办](../docs/开发待办.md)。本文的目录树、文件路径与命令均以 `backend/` 为基准。

### 本轮实现：企业模型、成员校验、真实登录与资源授权

2026-10-17确认管理员开通账号、员工账号密码登录；不开放公众注册。具体接口、迁移、首次管理员初始化、文件清单与四方面学习记录见[实施说明](../docs/企业身份与资源授权实施说明.md)，架构见[03](../03-架构设计.md) §4.4。不自动为旧会话、文档分配企业，也不在应用启动时创建默认账号。

定向93项通过，含随机端口真实HTTP、真实Mapper及隔离H2，失败/错误/跳过均为0。03增量脚本已生成但未执行到本机MySQL；H2不替代MySQL锁、迁移或容量验收。任务接口仅返回归属锚点，没有后台执行/进度恢复；文档接口仅返回获授权元数据，不含原件路径和正文。

在系统根目录使用已安装的Maven执行 `mvn -f backend/pom.xml '-Dtest=EnterpriseAuthorizationTest,EnterpriseSecurityHttpTest,AccountSecurityFilterTest,EnterpriseMemberServiceTest,EnterpriseAccountTransactionTest,AccountDataSafetyTest,CurrentUserServiceTest,ConversationServiceTest' test`。本机Wrapper有历史启动问题时沿用根README的缓存Maven命令。

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 3.3.5 | |
| Java | 17 | 字节码目标 17，可用 JDK 21 构建 |
| Maven | 3.9.9 | 由 Maven Wrapper 提供，无需预装 |
| MyBatis-Plus | 3.5.7 | 单表 CRUD、分页插件、逻辑删除 |
| MySQL | 8.0 | 库名 `rag_qa`，字符集 `utf8mb4` |

基础依赖包括 Web、Validation、AOP、MyBatis-Plus、MySQL 驱动、Lombok、Spring Security；JSON处理使用Web引入的Jackson。新增H2仅用于测试；未使用的security-test已移除。**未引入**向量数据库、大模型SDK、Actuator或接口文档组件；解析、索引和存储依赖另行论证。

## 环境要求

- JDK 17 或更高（`JAVA_HOME` 需指向 JDK 安装目录，不是 JRE）
- MySQL 8.0，已启动
- 不需要单独安装 Maven

## 快速开始

从系统根目录先进入后端目录，再执行下文命令：

```bash
cd backend
```

IDEA 以系统根目录打开项目时，重新加载 `backend/pom.xml`；应用及 JUnit 运行配置的工作目录设为 `$PROJECT_DIR$/backend`，保留已有环境变量。

### 1. 建库建表

`schema.sql` 位于**后端目录**（系统根目录下的 `backend/schema.sql`），应用启动时不会自动建表。以下仅供全新空库初始化参考；已有库先只读核对版本、结构、数据和备份，再评审增量迁移，不能重放整份脚本。本次文档更新不执行SQL：

```bash
mysql -uroot -p < schema.sql
```

脚本里含 `CREATE DATABASE IF NOT EXISTS rag_qa` 与 `USE rag_qa`，不必先手动建库。
注意 `CREATE TABLE` 未加 `IF NOT EXISTS`，重复执行会在已存在的表上报错，属正常现象。

### 2. 配置数据库连接

开发环境默认连 `localhost:3306/rag_qa`，账号 `root`。密码通过环境变量传入：

```bash
DB_PASSWORD=你的密码 ./mvnw spring-boot:run
```

可覆盖的变量（见 `application-dev.yml`）：`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`。

生产环境（`prod`）的数据源**没有默认值**，缺失时启动直接失败，避免误连本地库。变量清单见下一节。

### 生产环境（prod）配置清单

激活方式：`SPRING_PROFILES_ACTIVE=prod`，或启动参数 `--spring.profiles.active=prod`。
变量来源：`application-prod.yml`。

| 变量 | 必填 | 说明 | 示例 |
|---|---|---|---|
| `DB_URL` | 是 | 完整 JDBC 连接串，**不拼接**，需自行带上库名与参数 | `jdbc:mysql://10.0.0.5:3306/rag_qa?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=true` |
| `DB_USERNAME` | 是 | 数据库账号，建议使用仅授予 `rag_qa` 库权限的专用账号，不要用 root | `rag_qa_app` |
| `DB_PASSWORD` | 是 | 数据库密码，通过环境变量或密钥管理注入，**不要写进配置文件或提交到 Git** | - |
| `CORS_ALLOWED_ORIGINS` | 否 | 允许跨域的前端来源，多个用英文逗号分隔；**留空则不开放任何跨域** | `https://qa.example.com,https://admin.example.com` |
| `LOGIN_PER_ADDRESS_PER_MINUTE` | 否 | 单节点每直连地址每分钟登录尝试上限，默认10；按试点及网关部署评审调整 | `10` |
| `LOGIN_GLOBAL_PER_MINUTE` | 否 | 单节点全局每分钟登录尝试上限，默认120，不小于单地址上限 | `120` |

注意事项：

- 三个必填变量中任意一个缺失，应用会在启动时因占位符无法解析而直接失败，这是有意为之。
- `DB_URL` 与 dev 环境不同：dev 用 `DB_HOST` / `DB_PORT` / `DB_NAME` 拼接，prod 只认 `DB_URL`，**设置 `DB_HOST` 等变量在 prod 下不生效**。
- `CORS_ALLOWED_ORIGINS` 必须写成一行逗号分隔，不能写成多行列表；来源要写完整（含协议与端口），如 `https://qa.example.com`。
- 日志写入工作目录下的 `logs/rag-qa.log`，单文件 100MB 滚动、保留 30 天、总量上限 3GB，需确保运行账号对该目录有写权限。
- 单项配置（连接池大小等）在 `application-prod.yml` 中，目前连接池最大连接数为 20。
- 企业会话闲置30分钟、绝对8小时；Cookie仅跟随请求、HttpOnly、SameSite=Lax，prod开启Secure，生产须配HTTPS和明确的前后端来源。首版会话/登录限流在单节点，重启需重新登录；Redis和多节点会话未接入。
- 启用登录前先核对并执行独立03迁移；已有库不能重放`schema.sql`。首次管理员只能显式运行非Web首建命令，三个`KNOWINGORDER_BOOTSTRAP_*`变量见实施说明；完成后移除本次进程变量，不能加到正常启动配置中。

示例：

```bash
export SPRING_PROFILES_ACTIVE=prod
export DB_URL='jdbc:mysql://10.0.0.5:3306/rag_qa?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=true'
export DB_USERNAME='rag_qa_app'
export DB_PASSWORD='从密钥管理系统获取'
export CORS_ALLOWED_ORIGINS='https://qa.example.com'
java -jar target/rag-qa-0.0.1-SNAPSHOT.jar
```

### 3. 启动

```bash
./mvnw spring-boot:run
```

Windows cmd 用 `mvnw.cmd`、PowerShell 用 `.\mvnw.cmd` 代替 `./mvnw`。默认端口 **8080**。

### 4. 验证

```bash
curl http://localhost:8080/api/health
```

预期：

```json
{"code":0,"message":"成功","data":"UP","timestamp":1700000000000}
```

## 目录结构

```
src/main/java/com/ragqa/
├── RagQaApplication.java      启动类
├── common/                    跨层共享：Result、ResultCode、Constants、PageQuery、PageResult
│   └── aspect/                请求日志切面、敏感接口正文免记录注解
├── config/                    基础配置、外部 HTTP、问答预算、本机开发入口保护
├── controller/                接口层
├── domain/                    entity 数据表实体；dto 搜索、问答输入输出
├── exception/                 BusinessException、GlobalExceptionHandler
├── mapper/                    MyBatis-Plus Mapper
├── service/                   WebChatService：有界调度、部分回答、共享时限
├── utils/                     StrUtils、DateUtils、ValidateUtils、JsonUtils
└── rag/                       检索与问答流程
    ├── ingest/                本地文档解析与切分占位
    ├── retrieval/             Tavily 检索、证据去重与截断；向量检索尚未实现
    ├── llm/                   Responses 模型客户端、Prompt 版本注册
    └── chat/                  诉求分析、证据评估、生成与调用账本
src/main/resources/
├── application.yml            公共配置
├── application-dev.yml        开发环境
├── application-prod.yml       生产环境
└── prompts/                   plan、grade、generate 版本化模板与变更记录
```

基础表定义包括 `sys_user`、`rag_document`、`rag_chunk`、`rag_conversation`、`rag_message`；访客路线另已生成 `rag_guest_identity` 及会话归属/生命周期增量SQL（`sql/migrations/20261017_01_guest_identity.sql`、`20261017_02_guest_lifecycle.sql`），并有访客实体与Mapper。SQL文件存在不代表真实库已执行；访客方案当前冻结，不自动迁移。
表作用、逻辑关联与索引用途见 `schema.sql` 头部注释，不建物理外键。企业/成员、文档权限及辅助事务数据模型仍在目标架构阶段，不声称当前表已覆盖。

## 接口约定

### 商品查询与跳转开发单元

`POST /api/products/search`，JSON入参 `{"keyword":"咖啡"}`，返回 `Result<ProductSearchResult>`。当前 `provider=MOCK`、`simulated=true`，`notice`说明虚构目录；`items`包含 `id/title/merchant/referencePrice/currency/linkType/jumpUrl`。未知关键词返回空列表；非法输入或接口故障明确失败，不自动生成成功商品。

`ProductSearchController`只依赖 `ProductSearchService`；开发替身 `MockProductSearchService`只在dev且 `knowingorder.products.provider=mock`（未设置时dev默认mock）装配，无需新增凭据或改YAML。设置尚未实现的 `taobao` 模式会因缺少适配器明确启动失败，不静默回退。生产profile没有该开发Controller或Mock，真实渠道实现及企业生产入口仍待后续评审与联调。

本机保护沿用 `LocalChatFilter`，商品路径同样拒绝远程/转发请求并限制8KiB请求体；关键词最多80个UTF-16代码单元，界面限制与后端一致。结果列表不可变、并发查询无共享可变状态；不写库，无订单或额度副作用，因而本单元不引入业务事务或重试。

当前固定链接 `https://www.taobao.com/` 仅用于打开平台首页，不对应真实商品详情、不携带推广归因。实际店铺、售价、库存与履约未知。没有淘宝API调用、下单、支付或补贴动作，也未将商品查询接入现有问答Agent工具链。

可在IDEA重启原dev运行配置后，通过前端侧栏“商品查询”或以下命令验证：

```powershell
$body = [Text.Encoding]::UTF8.GetBytes('{"keyword":"咖啡"}')
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/products/search' -ContentType 'application/json; charset=utf-8' -Body $body
```

离线定向测试：在后端目录运行 `.\mvnw.cmd '-Dtest=ProductSearchTest' test`，预期25项通过。完整改动、四方面讲解和本轮验收见 [产品定版交接](../docs/开发上下文交接-知序产品定版-2026-10-17.md)。

所有接口返回统一的 `Result<T>`：

```json
{"code":0,"message":"成功","data":{},"timestamp":1700000000000}
```

- `code` 为 `0` 表示成功，非 `0` 见 `ResultCode`
- 业务异常抛 `BusinessException`，由 `GlobalExceptionHandler` 统一转换，HTTP 状态码仍为 200
- 参数校验失败、404、405 等框架层错误返回真实的 4xx/5xx 状态码
- 分页接口统一用 `PageQuery` 入参、`PageResult<T>` 出参
- 上传文件超限返回 HTTP 413（`code=413`）；异步线程池已满返回 HTTP 503（`code=503`）

## 上传与异步任务

上传限制（`application.yml`）：

| 配置 | 值 | 说明 |
|---|---|---|
| `spring.servlet.multipart.max-file-size` | 50MB | 单个文件上限 |
| `spring.servlet.multipart.max-request-size` | 100MB | 单次请求总上限 |
| `server.tomcat.max-swallow-size` | 100MB | 上传中止后最多继续读取的请求体；未读部分超过此值时仍可能断开连接 |

异步与定时任务（`config/AsyncConfig.java` 开启，线程池参数在 `spring.task.*`）：

| 线程池 | 用途 | 参数 |
|---|---|---|
| `rag-async-*` | `@Async` 后台任务，如文档解析 | 核心 4、最大 8、有界队列 100 |
| `rag-sched-*` | `@Scheduled` 定时任务，如扫描待解析文档 | 2 个线程 |

- 线程池由 Spring Boot 自动创建；以后若手动声明执行器，应同时明确 `@Async` 使用的执行器，避免自动配置退让后选错线程池。
- `@Async` 基于代理，同类方法互相调用不会异步。
- 返回 `void` 的异步方法异常会以 ERROR 记录；返回 `CompletableFuture` 的由调用方处理。
- 两个线程池停机时各最多等待 30 秒；超时不保证任务完成，后续业务需负责状态恢复与幂等处理。
- 线程池满时使用默认拒绝策略，不把后台任务转到请求线程执行；请求中提交任务被拒绝时返回 503，后台任务中的拒绝异常需由后台调用方处理。
- 这里只提供线程池与注解支持，尚未实现上传接口、解析任务或定时扫描逻辑。

## 构建与测试

### 本机联网问答最小闭环

当前新增 `POST /api/chat`，仅在 `dev` 环境注册，并只接受本机直连；禁止经过代理公开这个开发入口。模型继续使用已有 GPT 的 Responses 配置，不需要增加其他模型。真实搜索还需要 Tavily 搜索凭据，它不是第二个大模型。

在 IDEA 的应用运行配置中设置 `DASHSCOPE_BASE_URL`、`DASHSCOPE_API_KEY`、`LLM_MODEL`、`TAVILY_API_KEY`；密钥不写进文档、代码或日志。启动现有应用后，在本机 PowerShell 执行：

```powershell
$body = [System.Text.Encoding]::UTF8.GetBytes('{"question":"请联网核对 Java 17 的发布日期和长期支持性质，优先参考官方资料。"}')
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/chat' -ContentType 'application/json; charset=utf-8' -Body $body
```

返回外层 `Result<ChatAnswer>`：`data.status` 为 `COMPLETED`、`PARTIAL` 或 `NO_ANSWER`，各问题另有 `status`、`basis`、`answer`、`message`、`sources`。`GENERAL` 表示未联网的一般建议，`WEB` 表示检索路径；不能仅凭 `code=0` 判定全部问题回答成功。正文为纯文本，引用形如 `[q1-s1]`，按同题 `sources.id` 查来源。所有路径先结束加载，再展示各题提示；不得将正文直接当 HTML 渲染。

本地初值：问题最多 500 个 Unicode 字符、单请求最多三问、总时限 90 秒，同时受理两次请求，每次最多两个独立分支并行；依赖前置未完成的题不盲目执行。每题最多五条资料、正文各 800 字，最多补查一轮；每次请求模型调用上限 10、逻辑搜索上限 6。搜索内部最多两次传输尝试，理论传输上界 12，仍受总时间限制；模型不自动重试。可用 `rag.chat.*` 或对应环境变量覆盖配置，生产阈值必须另行压测。

普通离线回归包含模拟供应商的 HTTP 闭环和故障测试。以下真实测试需在对应 JUnit 或 Maven 进程配置变量并显式开启，不默认产生费用：

```powershell
# 真实 GPT + 固定测试搜索资料，验证正式模板及三问处理；这不代表真实搜索连通
$env:RUN_GPT_FLOW_IT='true'
mvn '-Dtest=WebChatLiveIT#configuredGptRunsProductionPromptsWithControlledEvidence+configuredGptKeepsAllThreeMixedRequestsWithoutPriorityClarification' test

# 已配置 Tavily 后，验证真实搜索与 GPT 的一次端到端请求
$env:RUN_WEB_CHAT_LIVE_TEST='true'
mvn '-Dtest=WebChatLiveIT#configuredSearchAndGptCompleteRealOnlineQuestion' test

# 30 题真实评测，会产生搜索和模型费用；报告输出到 target/eval/web-chat-v1.json
$env:RUN_WEB_CHAT_EVAL='true'
mvn '-Dtest=WebChatEvaluationIT' test
```

本轮不写数据库、不持久化任务，不涉及新业务事务及回滚；同步入口没有跨刷新恢复、消息去重或处理中追问合并。尚未接入正式身份认证、SSE、Redis、内容审核及生产限流，不作为上线验收完成。模块说明与测试证据见 [联网问答最小闭环说明](../docs/联网问答最小闭环说明.md) 与 [开发待办](../docs/开发待办.md)。

```bash
./mvnw -B verify                    # 编译 + 单元测试
./mvnw test -Dtest=MapperCrudIT     # 需要真实 MySQL，验证 Mapper 增删改查
```

`MapperCrudIT` 故意以 `IT` 结尾，surefire 默认不匹配该后缀，因此不连数据库的环境上 `./mvnw test` 也能通过。它用 `@Transactional` 回滚，不会残留测试数据。

## 本机注意事项

**1. 中文日志乱码**

Maven 自身的 JVM 在 Windows 上按 GBK 转发输出，导致测试日志里的中文显示为乱码（源码文件本身没问题）。加上这个环境变量即可：

```bash
MAVEN_OPTS="-Dstdout.encoding=UTF-8" ./mvnw test
```

**2. `JAVA_HOME` 必须指向 JDK**

指向 JRE 会导致编译失败。可先确认：

```bash
"$JAVA_HOME/bin/java" -version
```

**3. Maven 本地仓库位置**

若 `~/.m2/settings.xml` 里配置了 `localRepository`，依赖会下载到该路径，而不是默认的 `~/.m2/repository`。排查依赖问题时注意确认实际路径。

## 提交规范

提交信息使用约定式提交（Conventional Commits），如 `feat:`、`fix:`、`chore:`、`docs:`。

## 开发约定

详见系统根目录的 [CLAUDE.md](../CLAUDE.md)（执行版）与 [AGENTS.md](../AGENTS.md)（完整规则）。要点：小步改动、精确编辑、文件统一 UTF-8 + LF、不碰密钥。
