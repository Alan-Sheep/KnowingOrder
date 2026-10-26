package com.ragqa.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.entity.RagKnowledgeSpace;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.EnterpriseDirectoryMapper;
import com.ragqa.mapper.EnterpriseResourceMapper;
import com.ragqa.mapper.KnowledgeSpaceMapper;
import com.ragqa.mapper.SysUserMapper;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.service.impl.KnowledgeSpaceServiceImpl;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 显式运行的本机MySQL验收：测试企业独立提交，故障后从事务外查库，最后仅清理本次数据。 */
class KnowledgeSpaceMysqlIT {
    private final String marker = "it_space_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private EnterpriseDirectoryMapper directory;
    private EnterpriseResourceMapper resources;
    private EnterpriseMemberService members;
    private KnowledgeSpaceMapper mapper;
    private KnowledgeSpaceService service;
    private long enterpriseId;
    private long userId;
    private long memberId;

    @BeforeEach
    void setup() throws Exception {
        String url = required("DB_URL");
        // 防误连远端或生产库；凭据只取JUnit运行环境，不输出URL或密码。
        assertThat(url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]+(\\?.*)?"))
                .as("实库测试仅接受本机带显式端口的MySQL地址").isTrue();
        var dataSource = new DriverManagerDataSource(url, required("DB_USERNAME"), required("DB_PASSWORD"));
        var connectionProperties = new Properties();
        connectionProperties.setProperty("connectTimeout", "3000");
        connectionProperties.setProperty("socketTimeout", "10000");
        dataSource.setConnectionProperties(connectionProperties);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(8);
        assertThat(jdbc.queryForObject("SELECT VERSION()", String.class)).startsWith("8.");
        manager = new DataSourceTransactionManager(dataSource);
        manager.setDefaultTimeout(8);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setCacheEnabled(false);
        config.setDefaultStatementTimeout(8);
        // 不启用SQL参数日志，测试账号也不输出口令散列。
        config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(config);
        for (Class<?> type : List.of(SysUserMapper.class, EnterpriseDirectoryMapper.class,
                EnterpriseResourceMapper.class, KnowledgeSpaceMapper.class)) config.addMapper(type);
        var sql = new SqlSessionTemplate(factory.getObject());
        directory = sql.getMapper(EnterpriseDirectoryMapper.class);
        resources = sql.getMapper(EnterpriseResourceMapper.class);
        members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)), directory);
        mapper = sql.getMapper(KnowledgeSpaceMapper.class);
        service = transactional(mapper);
        // 先提交独立测试企业；不使用已有企业、管理员，也不执行schema或迁移。
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            enterpriseId = insert("INSERT INTO sys_enterprise(name) VALUES(?)", marker);
            userId = insert("INSERT INTO sys_user(username,password_hash) VALUES(?,?)", marker, "不可登录的测试占位");
            memberId = insert("INSERT INTO sys_enterprise_member(enterprise_id,user_id,role) VALUES(?,?,'ADMIN')", enterpriseId, userId);
        });
    }

    @AfterEach
    void cleanup() {
        if (jdbc == null || enterpriseId == 0) return;
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            // 企业主键与唯一随机标记双重核对，只删除本次测试拥有的数据。
            int owned = jdbc.queryForObject("SELECT COUNT(*) FROM sys_enterprise WHERE id=? AND name=?", Integer.class, enterpriseId, marker);
            if (owned == 0) return;
            jdbc.update("DELETE FROM rag_knowledge_grant WHERE enterprise_id=?", enterpriseId);
            jdbc.update("DELETE FROM rag_knowledge_space WHERE enterprise_id=?", enterpriseId);
            jdbc.update("DELETE FROM sys_enterprise_member WHERE enterprise_id=? AND user_id=?", enterpriseId, userId);
            jdbc.update("DELETE FROM sys_enterprise WHERE id=? AND name=?", enterpriseId, marker);
            jdbc.update("DELETE FROM sys_user WHERE id=? AND username=?", userId, marker);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_enterprise WHERE id=?", Integer.class, enterpriseId)).isZero();
    }

    @Test
    void committedSpaceHasGeneratedKeyAndExplicitMaintainGrant() {
        var created = service.createSpace(principal(), enterpriseId, " 研发'知识库😀 ");
        assertThat(created.id()).isPositive();
        assertThat(created.name()).isEqualTo("研发'知识库😀");
        assertThat(created.permissionVersion()).isEqualTo(1L);
        var scope = members.requireMember(principal(), enterpriseId);
        assertThat(resources.findSpace(scope, created.id())).isEqualTo(created);
        var grant = jdbc.queryForMap("SELECT member_id,department_id,permission FROM rag_knowledge_grant WHERE enterprise_id=? AND knowledge_space_id=?", enterpriseId, created.id());
        assertThat(((Number) grant.get("member_id")).longValue()).isEqualTo(memberId);
        assertThat(grant.get("department_id")).isNull();
        assertThat(grant.get("permission")).isEqualTo("MAINTAIN");
    }

    @Test
    void failureAfterGrantWriteRollsBackBothTablesOnMysql() {
        var broken = transactional(new KnowledgeSpaceMapper() {
            @Override
            public int insertSpace(EnterpriseMemberContext scope, RagKnowledgeSpace space) {
                return mapper.insertSpace(scope, space);
            }
            @Override
            public int insertCreatorGrant(EnterpriseMemberContext scope, long spaceId) {
                assertThat(mapper.insertCreatorGrant(scope, spaceId)).isEqualTo(1);
                // 第二次同成员授权触发真实唯一约束，不使用假的成功返回值。
                return mapper.insertCreatorGrant(scope, spaceId);
            }
        });
        assertThatThrownBy(() -> broken.createSpace(principal(), enterpriseId, "回滚验证"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertCount("rag_knowledge_space", 0);
        assertCount("rag_knowledge_grant", 0);
    }

    @Test
    void revokedAdministratorCannotWrite() {
        jdbc.update("UPDATE sys_enterprise_member SET status=1,permission_version=permission_version+1 WHERE id=?", memberId);
        assertThatThrownBy(() -> service.createSpace(principal(), enterpriseId, "禁止写入")).isInstanceOf(BusinessException.class);
        assertCount("rag_knowledge_space", 0);
        assertCount("rag_knowledge_grant", 0);
    }

    @Test
    void independentConcurrentRequestsEachCommitTheirOwnSpace() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Long> create = () -> service.createSpace(principal(), enterpriseId, "允许同名").id();
            var first = executor.submit(create);
            var second = executor.submit(create);
            assertThat(first.get(15, TimeUnit.SECONDS)).isNotEqualTo(second.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        assertCount("rag_knowledge_space", 2);
        assertCount("rag_knowledge_grant", 2);
    }

    private KnowledgeSpaceService transactional(KnowledgeSpaceMapper target) {
        var proxy = new ProxyFactory(new KnowledgeSpaceServiceImpl(members, directory, target));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (KnowledgeSpaceService) proxy.getProxy();
    }

    private AuthenticatedUserPrincipal principal() { return new AuthenticatedUserPrincipal(userId, 1); }

    private void assertCount(String table, int expected) {
        // 表名只来自本类两个编译期常量调用，绝不接受用户输入。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE enterprise_id=?", Integer.class, enterpriseId)).isEqualTo(expected);
    }

    private long insert(String sql, Object... values) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private static String required(String key) {
        String value = System.getenv(key);
        assertThat(value).as("请在本次JUnit运行配置中设置环境变量 %s，密钥不用发给AI", key).isNotBlank();
        return value;
    }
}
