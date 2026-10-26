package com.ragqa.service;

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
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.Principal;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 通过真实Spring事务代理和隔离H2验证提交/回滚；不替代MySQL锁行为及HTTP验收。 */
class KnowledgeSpaceTransactionTest {
    private static final AuthenticatedUserPrincipal ADMIN = new AuthenticatedUserPrincipal(3, 1);
    private EnterpriseDatabase db;
    private EnterpriseDirectoryMapper directory;
    private EnterpriseMemberService members;
    private KnowledgeSpaceMapper mapper;
    private KnowledgeSpaceService spaces;

    @BeforeEach
    void setup() throws Exception {
        db = new EnterpriseDatabase();
        db.seed();
        db.factory.getConfiguration().addMapper(KnowledgeSpaceMapper.class);
        var sql = new SqlSessionTemplate(db.factory);
        directory = sql.getMapper(EnterpriseDirectoryMapper.class);
        members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)),
                directory);
        mapper = sql.getMapper(KnowledgeSpaceMapper.class);
        spaces = service(members, mapper);
    }

    @AfterEach
    void close() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void successfulCreationCommitsSpaceAndExplicitCreatorPermission() {
        var created = spaces.createSpace(ADMIN, 1L, " \t研发知识库\u3000");
        assertThat(created.id()).isPositive();
        assertThat(created.name()).isEqualTo("研发知识库");
        assertThat(created.permissionVersion()).isEqualTo(1L);
        // 方法返回后以事务外连接读取，不能靠测试自身的回滚掩盖未提交或半成品。
        assertThat(db.jdbc.queryForObject("SELECT enterprise_id FROM rag_knowledge_space WHERE id = ?",
                Long.class, created.id())).isEqualTo(1L);
        var grant = db.jdbc.queryForMap("SELECT * FROM rag_knowledge_grant WHERE knowledge_space_id = ?",
                created.id());
        assertThat(((Number) grant.get("member_id")).longValue()).isEqualTo(103L);
        assertThat(grant.get("department_id")).isNull();
        assertThat(grant.get("permission")).isEqualTo("MAINTAIN");
        var resources = db.session.getMapper(EnterpriseResourceMapper.class);
        assertThat(resources.findSpace(directory.findActiveMember(3, 1, 1), created.id())).isEqualTo(created);
        assertThat(resources.findSpace(directory.findActiveMember(1, 1, 1), created.id())).isNull();
    }

    @Test
    void maximumLengthIsCheckedAfterTrimming() {
        String name = "知".repeat(128);
        assertThat(spaces.createSpace(ADMIN, 1L, " " + name + " ").name()).isEqualTo(name);
    }

    @ParameterizedTest
    @NullSource
    @MethodSource("invalidNames")
    void invalidNameIsRejectedBeforeWriting(String name) {
        assertBusinessCode(() -> spaces.createSpace(ADMIN, 1L, name), 400);
        assertSeedCounts();
    }

    private static Stream<String> invalidNames() {
        return Stream.of("", " \t\n\u3000", "文".repeat(129));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void invalidEnterpriseIdentifierIsRejected(Long enterpriseId) {
        assertBusinessCode(() -> spaces.createSpace(ADMIN, enterpriseId, "知识库"), 400);
        assertSeedCounts();
    }

    @Test
    void missingOrUnversionedPrincipalIsRejected() {
        assertBusinessCode(() -> spaces.createSpace(null, 1L, "知识库"), 401);
        assertBusinessCode(() -> spaces.createSpace(new AuthenticatedUserPrincipal(3), 1L, "知识库"), 401);
        assertSeedCounts();
    }

    @ParameterizedTest
    @CsvSource({"1,1", "2,1", "3,2", "3,9999"})
    void nonAdministratorOrUnavailableEnterpriseGetsSameFailure(long userId, long enterpriseId) {
        assertBusinessCode(() -> spaces.createSpace(new AuthenticatedUserPrincipal(userId, 1), enterpriseId,
                "知识库"), 404);
        assertSeedCounts();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE sys_user SET status = 1 WHERE id = 3",
            "UPDATE sys_user SET deleted = 1 WHERE id = 3",
            "UPDATE sys_user SET auth_version = 2 WHERE id = 3"
    })
    void revokedAccountCannotStartCreation(String fixedStatement) {
        db.jdbc.update(fixedStatement);
        assertBusinessCode(() -> spaces.createSpace(ADMIN, 1L, "知识库"), 401);
        assertSeedCounts();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE sys_enterprise SET status = 1 WHERE id = 1",
            "UPDATE sys_enterprise_member SET status = 1 WHERE id = 103",
            "UPDATE sys_enterprise_member SET status = 2 WHERE id = 103",
            "UPDATE sys_enterprise_member SET role = 'EMPLOYEE' WHERE id = 103"
    })
    void inactiveMembershipOrLostAdministratorRoleCannotCreate(String fixedStatement) {
        db.jdbc.update(fixedStatement);
        assertBusinessCode(() -> spaces.createSpace(ADMIN, 1L, "知识库"), 404);
        assertSeedCounts();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE sys_enterprise SET status = 1 WHERE id = 1",
            "UPDATE sys_enterprise SET permission_version = 2 WHERE id = 1",
            "UPDATE sys_enterprise_member SET status = 1 WHERE id = 103",
            "UPDATE sys_enterprise_member SET status = 2 WHERE id = 103",
            "UPDATE sys_enterprise_member SET role = 'EMPLOYEE' WHERE id = 103",
            "UPDATE sys_enterprise_member SET department_id = NULL WHERE id = 103",
            "UPDATE sys_enterprise_member SET permission_version = 2 WHERE id = 103",
            "UPDATE sys_user SET status = 1 WHERE id = 3",
            "UPDATE sys_user SET deleted = 1 WHERE id = 3",
            "UPDATE sys_user SET auth_version = 2 WHERE id = 3"
    })
    void changeCommittedBetweenInitialCheckAndLockRejectsOldSnapshot(String fixedStatement) {
        EnterpriseMemberContext before = directory.findActiveMember(3, 1, 1);
        var afterSnapshot = new EnterpriseMemberService() {
            @Override
            public java.util.List<Workspace> listWorkspaces(Principal principal) {
                return members.listWorkspaces(principal);
            }

            @Override
            public EnterpriseMemberContext requireMember(Principal principal, Long enterpriseId) {
                var snapshot = members.requireMember(principal, enterpriseId);
                // 独立事务提交撤权，外层创建失败不能顺带把撤权回滚掉。
                var external = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource));
                external.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                external.executeWithoutResult(status -> assertThat(db.jdbc.update(fixedStatement)).isEqualTo(1));
                return snapshot;
            }

            @Override
            public EnterpriseMemberContext requireCurrent(EnterpriseMemberContext context) {
                return members.requireCurrent(context);
            }
        };
        var underTest = service(afterSnapshot, mapper);
        assertBusinessCode(() -> underTest.createSpace(ADMIN, 1L, "知识库"), 404);
        assertSeedCounts();
        assertThat(directory.findActiveMember(3, 1, 1)).isNotEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    void persistenceFailureRollsBackBothWritesAndPreservesExistingRows(Fault fault) {
        var underTest = service(members, faultyMapper(fault));
        if (fault == Fault.GRANT_UNAVAILABLE) {
            assertThatThrownBy(() -> underTest.createSpace(ADMIN, 1L, "必须回滚"))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        } else if (fault == Fault.SPACE_SQL_FAILURE || fault == Fault.GRANT_DUPLICATE_AFTER_WRITE) {
            assertThatThrownBy(() -> underTest.createSpace(ADMIN, 1L, "必须回滚"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } else {
            int code = fault == Fault.MISSING_KEY || fault == Fault.INVALID_KEY ? 500 : 404;
            assertBusinessCode(() -> underTest.createSpace(ADMIN, 1L, "必须回滚"), code);
        }
        assertSeedCounts();
        assertThat(db.jdbc.queryForObject("SELECT name FROM rag_knowledge_space WHERE id = 401", String.class))
                .isEqualTo("成员授权");
    }

    @Test
    void simultaneousIndependentRequestsCommitSeparateSpacesAndGrants() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<Long> create = () -> {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发测试启动等待超时");
                }
                return spaces.createSpace(ADMIN, 1L, "允许同名的独立请求").id();
            };
            var first = executor.submit(create);
            var second = executor.submit(create);
            start.countDown();
            Long firstId = first.get(10, TimeUnit.SECONDS);
            Long secondId = second.get(10, TimeUnit.SECONDS);
            assertThat(firstId).isNotEqualTo(secondId);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_space", Integer.class))
                    .isEqualTo(6);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_grant", Integer.class))
                    .isEqualTo(5);
            for (Long id : new Long[] {firstId, secondId}) {
                assertThat(db.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_knowledge_grant WHERE knowledge_space_id = ? AND member_id = 103",
                        Integer.class, id)).isEqualTo(1);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private KnowledgeSpaceMapper faultyMapper(Fault fault) {
        return new KnowledgeSpaceMapper() {
            @Override
            public int insertSpace(EnterpriseMemberContext scope, RagKnowledgeSpace space) {
                int rows = mapper.insertSpace(scope, space);
                assertThat(rows).isEqualTo(1);
                if (fault == Fault.SPACE_ZERO) {
                    return 0;
                }
                if (fault == Fault.MISSING_KEY) {
                    space.setId(null);
                } else if (fault == Fault.INVALID_KEY) {
                    space.setId(0L);
                } else if (fault == Fault.SPACE_SQL_FAILURE) {
                    db.jdbc.update("INSERT INTO rag_knowledge_space(enterprise_id, name) VALUES(1, NULL)");
                }
                return rows;
            }

            @Override
            public int insertCreatorGrant(EnterpriseMemberContext scope, long spaceId) {
                if (fault == Fault.GRANT_ZERO) {
                    return 0;
                }
                if (fault == Fault.GRANT_UNAVAILABLE) {
                    throw new DataAccessResourceFailureException("测试注入：授权存储不可用");
                }
                assertThat(mapper.insertCreatorGrant(scope, spaceId)).isEqualTo(1);
                // 再次真实写入触发唯一约束，验证授权已写入后发生异常也会整体回滚。
                return mapper.insertCreatorGrant(scope, spaceId);
            }
        };
    }

    private KnowledgeSpaceService service(EnterpriseMemberService memberService, KnowledgeSpaceMapper writes) {
        var target = new KnowledgeSpaceServiceImpl(memberService, directory, writes);
        var transaction = new TransactionInterceptor();
        transaction.setTransactionManager(new DataSourceTransactionManager(db.dataSource));
        transaction.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(transaction);
        return (KnowledgeSpaceService) proxy.getProxy();
    }

    private void assertSeedCounts() {
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_space", Integer.class)).isEqualTo(4);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_grant", Integer.class)).isEqualTo(3);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(3);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_enterprise_member", Integer.class)).isEqualTo(4);
    }

    private void assertBusinessCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    private enum Fault {
        SPACE_ZERO, MISSING_KEY, INVALID_KEY, SPACE_SQL_FAILURE, GRANT_ZERO, GRANT_UNAVAILABLE,
        GRANT_DUPLICATE_AFTER_WRITE
    }
}
