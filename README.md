# RAG 智能问答系统 · 后端

基于 Spring Boot 的后端服务。当前**只有非业务的基础骨架与通用设施**，RAG 业务逻辑（文档切分、向量检索、大模型调用）尚未实现。

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Spring Boot | 3.3.5 | |
| Java | 17 | 字节码目标 17，可用 JDK 21 构建 |
| Maven | 3.9.9 | 由 Maven Wrapper 提供，无需预装 |
| MyBatis-Plus | 3.5.7 | 单表 CRUD、分页插件、逻辑删除 |
| MySQL | 8.0 | 库名 `rag_qa`，字符集 `utf8mb4` |

依赖只有 Web、Validation、AOP、MyBatis-Plus、MySQL 驱动、Lombok。**未引入**向量数据库、大模型 SDK、Actuator、接口文档等组件。

## 环境要求

- JDK 17 或更高（`JAVA_HOME` 需指向 JDK 安装目录，不是 JRE）
- MySQL 8.0，已启动
- 不需要单独安装 Maven

## 快速开始

### 1. 建库建表

`schema.sql` 在**仓库根目录**，应用启动时不会自动建表，需要手动执行一次：

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

注意事项：

- 三个必填变量中任意一个缺失，应用会在启动时因占位符无法解析而直接失败，这是有意为之。
- `DB_URL` 与 dev 环境不同：dev 用 `DB_HOST` / `DB_PORT` / `DB_NAME` 拼接，prod 只认 `DB_URL`，**设置 `DB_HOST` 等变量在 prod 下不生效**。
- `CORS_ALLOWED_ORIGINS` 必须写成一行逗号分隔，不能写成多行列表；来源要写完整（含协议与端口），如 `https://qa.example.com`。
- 日志写入工作目录下的 `logs/rag-qa.log`，单文件 100MB 滚动、保留 30 天、总量上限 3GB，需确保运行账号对该目录有写权限。
- 单项配置（连接池大小等）在 `application-prod.yml` 中，目前连接池最大连接数为 20。

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

Windows cmd / PowerShell 用 `mvnw.cmd` 代替 `./mvnw`。默认端口 **8080**。

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
│   └── aspect/                LogAspect 请求日志切面
├── config/                    JacksonConfig、MybatisPlusConfig、WebMvcConfig
├── controller/                接口层
├── domain/entity/             实体：BaseEntity / SoftDeleteEntity 及 5 张表对应实体
├── exception/                 BusinessException、GlobalExceptionHandler
├── mapper/                    MyBatis-Plus Mapper
├── service/                   业务服务层（空，待实现）
├── utils/                     StrUtils、DateUtils、ValidateUtils、JsonUtils
└── rag/                       RAG 引擎（占位，待实现）
    ├── ingest/                文档解析与切分
    ├── retrieval/             向量检索
    ├── llm/                   大模型调用
    └── chat/                  问答编排
src/main/resources/
├── application.yml            公共配置
├── application-dev.yml        开发环境
└── application-prod.yml       生产环境
```

数据表：`sys_user`、`rag_document`、`rag_chunk`、`rag_conversation`、`rag_message`。
表作用、逻辑关联与索引用途见 `schema.sql` 头部注释。

## 接口约定

所有接口返回统一的 `Result<T>`：

```json
{"code":0,"message":"成功","data":{},"timestamp":1700000000000}
```

- `code` 为 `0` 表示成功，非 `0` 见 `ResultCode`
- 业务异常抛 `BusinessException`，由 `GlobalExceptionHandler` 统一转换，HTTP 状态码仍为 200
- 参数校验失败、404、405 等框架层错误返回真实的 4xx/5xx 状态码
- 分页接口统一用 `PageQuery` 入参、`PageResult<T>` 出参

## 构建与测试

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

详见 `CLAUDE.md`（执行版）与 `AGENTS.md`（完整规则）。要点：小步改动、精确编辑、文件统一 UTF-8 + LF、不碰密钥。
