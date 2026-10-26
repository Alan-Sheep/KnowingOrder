package com.ragqa.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.ragqa.config.MybatisPlusConfig;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.mapper.*;
import com.ragqa.rag.ingest.Utf8TextParser;
import com.ragqa.service.impl.*;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 显式运行的真实MySQL入库验收；不执行DDL、不使用已有企业、不读取IDE配置。 */
class KnowledgeIngestMysqlIT {
    private final String marker = "it_ingest_" + UUID.randomUUID().toString().replace("-", "");
    @TempDir Path root;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private SqlSessionTemplate sql;
    private EnterpriseMemberService members;
    private KnowledgeDocumentService service;
    private long enterpriseId;
    private long userId;
    private long spaceId;

    @BeforeEach
    void setup() throws Exception {
        String url = required("DB_URL");
        assertThat(url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]+(\\?.*)?"))
                .as("仅接受本机带显式端口的开发库，不输出地址或凭据").isTrue();
        var dataSource = new DriverManagerDataSource(url, required("DB_USERNAME"), required("DB_PASSWORD"));
        var properties = new Properties();
        properties.setProperty("connectTimeout", "3000");
        properties.setProperty("socketTimeout", "10000");
        dataSource.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(8);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.");
        // 缺少新迁移时先失败，不对库做自动修复或半套初始化。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='rag_document' AND COLUMN_NAME IN ('owner_member_id','published_version_id','revision')", Integer.class)).isEqualTo(3);
        jdbc.queryForObject("SELECT COUNT(*) FROM rag_document_version WHERE 1=0", Integer.class);
        manager = new DataSourceTransactionManager(dataSource);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setCacheEnabled(false);
        config.setDefaultStatementTimeout(8);
        config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        config.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
        for (Class<?> type : List.of(SysUserMapper.class, EnterpriseDirectoryMapper.class, EnterpriseResourceMapper.class,
                KnowledgeSpaceMapper.class, KnowledgeManagementMapper.class, KnowledgeDocumentMapper.class)) config.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(config);
        sql = new SqlSessionTemplate(factory.getObject());
        members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)), sql.getMapper(EnterpriseDirectoryMapper.class));
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            enterpriseId = insert("INSERT INTO sys_enterprise(name) VALUES(?)", marker);
            userId = insert("INSERT INTO sys_user(username,password_hash) VALUES(?,?)", marker, "不可登录的测试占位");
            insert("INSERT INTO sys_enterprise_member(enterprise_id,user_id,role) VALUES(?,?,'ADMIN')", enterpriseId, userId);
        });
        var proxy = new ProxyFactory(new KnowledgeSpaceServiceImpl(members, sql.getMapper(EnterpriseDirectoryMapper.class), sql.getMapper(KnowledgeSpaceMapper.class)));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        spaceId = ((KnowledgeSpaceService) proxy.getProxy()).createSpace(principal(), enterpriseId, "独立入库测试").id();
        service = service(sql.getMapper(KnowledgeDocumentMapper.class));
    }

    @AfterEach
    void cleanup() {
        if (manager == null || enterpriseId == 0) return;
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_enterprise WHERE id=? AND name=?", Integer.class, enterpriseId, marker) != 1) return;
            jdbc.update("DELETE c FROM rag_chunk c JOIN rag_document d ON d.id=c.document_id WHERE d.enterprise_id=?", enterpriseId);
            jdbc.update("DELETE FROM rag_document_version WHERE enterprise_id=?", enterpriseId);
            jdbc.update("DELETE FROM rag_document WHERE enterprise_id=?", enterpriseId);
            jdbc.update("DELETE FROM rag_knowledge_grant WHERE enterprise_id=?", enterpriseId);
            jdbc.update("DELETE FROM rag_knowledge_space WHERE enterprise_id=?", enterpriseId);
            jdbc.update("DELETE FROM sys_enterprise_member WHERE enterprise_id=? AND user_id=?", enterpriseId, userId);
            jdbc.update("DELETE FROM sys_enterprise WHERE id=? AND name=?", enterpriseId, marker);
            jdbc.update("DELETE FROM sys_user WHERE id=? AND username=?", userId, marker);
        });
    }

    @Test
    void uploadParsePublishWithdrawAndOriginalUseActualMysqlMappings() {
        var first = service.upload(principal(), enterpriseId, spaceId, metadata(), file("研发'制度😀\n".repeat(180)));
        long document = first.documentId();
        var ready = service.parse(principal(), enterpriseId, spaceId, document, first.id(), 1L);
        assertThat(ready.chunkCount()).isGreaterThan(1);
        assertThat(service.chunks(principal(), enterpriseId, spaceId, document, first.id(), 1, 20).getTotal()).isEqualTo(ready.chunkCount());
        var published = service.lifecycle(principal(), enterpriseId, spaceId, document, first.id(), 2L, "PUBLISH");
        assertThat(published.publishedVersionId()).isEqualTo(first.id());
        assertThat(new String(service.original(principal(), enterpriseId, spaceId, document, first.id()).bytes(), StandardCharsets.UTF_8))
                .isEqualTo("研发'制度😀\n".repeat(180));
        assertThat(service.lifecycle(principal(), enterpriseId, spaceId, document, first.id(), 3L, "WITHDRAW").publishedVersionId()).isNull();
    }

    @Test
    void simultaneousIdenticalUploadsCommitOneVersionAndOriginal() throws Exception {
        var request = metadata();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var jobs = executor.invokeAll(List.of(
                    () -> service.upload(principal(), enterpriseId, spaceId, request, file("同一内容")),
                    () -> service.upload(principal(), enterpriseId, spaceId, request, file("同一内容"))), 20, TimeUnit.SECONDS);
            assertThat(jobs.get(0).get()).isEqualTo(jobs.get(1).get());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_document_version WHERE enterprise_id=?", Integer.class, enterpriseId)).isEqualTo(1);
            try (var entries = Files.list(root)) { assertThat(entries.count()).isEqualTo(1); }
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void failureAfterVersionInsertRollsBackBothTablesAndNewOriginal() throws Exception {
        var delegate = sql.getMapper(KnowledgeDocumentMapper.class);
        var mapper = spy(delegate);
        doAnswer(call -> {
            delegate.insertVersion(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            throw new IllegalStateException("测试提交前失败");
        }).when(mapper).insertVersion(any(), anyLong(), any());
        assertThatThrownBy(() -> service(mapper).upload(principal(), enterpriseId, spaceId, metadata(), file("不得半提交")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_document WHERE enterprise_id=?", Integer.class, enterpriseId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_document_version WHERE enterprise_id=?", Integer.class, enterpriseId)).isZero();
        try (var entries = Files.list(root)) { assertThat(entries.count()).isZero(); }
    }

    private KnowledgeDocumentService service(KnowledgeDocumentMapper mapper) {
        return new KnowledgeDocumentService(members, new EnterpriseResourceServiceImpl(members, sql.getMapper(EnterpriseResourceMapper.class)),
                sql.getMapper(KnowledgeManagementMapper.class), mapper, new PrivateKnowledgeFiles(root.toString()), new Utf8TextParser(), manager);
    }
    private AuthenticatedUserPrincipal principal() { return new AuthenticatedUserPrincipal(userId, 1); }
    private KnowledgeDocumentService.Upload metadata() { return new KnowledgeDocumentService.Upload(UUID.randomUUID().toString(), null, null, "测试'制度", null, null); }
    private MockMultipartFile file(String text) { return new MockMultipartFile("file", "制度.md", "text/markdown", text.getBytes(StandardCharsets.UTF_8)); }
    private long insert(String statement, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var prepared = connection.prepareStatement(statement, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) prepared.setObject(i + 1, values[i]);
            return prepared;
        }, key);
        return key.getKey().longValue();
    }
    private static String required(String name) {
        String value = System.getenv(name);
        assertThat(value).as("请在本次JUnit配置设置%s，凭据不要发给AI", name).isNotBlank();
        return value;
    }
}
