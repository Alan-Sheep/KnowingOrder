package com.ragqa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.config.MybatisPlusConfig;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.EnterpriseResources;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.EnterpriseDirectoryMapper;
import com.ragqa.mapper.EnterpriseResourceMapper;
import com.ragqa.mapper.SysUserMapper;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.service.impl.EnterpriseResourceServiceImpl;
import com.ragqa.support.EnterpriseDatabase;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 真实MyBatis+隔离H2执行SQL；覆盖授权行过滤，不能代替目标MySQL验收。 */
class EnterpriseAuthorizationTest {
    private EnterpriseDatabase db;
    private EnterpriseMemberService members;
    private EnterpriseResourceService resources;
    private EnterpriseResourceMapper mapper;
    private SqlSession resourceSession;
    private EnterpriseMemberContext owner;

    @BeforeEach
    void setup() throws Exception {
        db = new EnterpriseDatabase();
        db.seed();
        // 使用与应用一致的分页插件，实际执行授权COUNT和分页SQL，不用内存截取冒充分页。
        db.factory.getConfiguration().addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor());
        // 测试工具已打开旧会话；插件只会包装之后新建的执行器，必须另开资源查询会话。
        resourceSession = db.factory.openSession(true);
        members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(db.session.getMapper(SysUserMapper.class)),
                db.session.getMapper(EnterpriseDirectoryMapper.class));
        mapper = resourceSession.getMapper(EnterpriseResourceMapper.class);
        resources = new EnterpriseResourceServiceImpl(members, mapper);
        owner = members.requireMember(new AuthenticatedUserPrincipal(1, 1), 1L);
    }

    @AfterEach
    void close() throws Exception {
        if (resourceSession != null) {
            resourceSession.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @Test
    void workspacesAreResolvedFromAccountAndOnlyContainActiveMemberships() {
        var principal = new AuthenticatedUserPrincipal(1, 1);
        assertThat(members.listWorkspaces(principal)).containsExactly(
                new EnterpriseMemberService.Workspace(1L, "甲企业", "EMPLOYEE"),
                new EnterpriseMemberService.Workspace(2L, "乙企业", "EMPLOYEE"));
        assertThat(members.listWorkspaces(new AuthenticatedUserPrincipal(2, 1)))
                .extracting(EnterpriseMemberService.Workspace::enterpriseId).containsExactly(1L);
        db.jdbc.update("UPDATE sys_enterprise_member SET status=1 WHERE id=101");
        assertThat(members.listWorkspaces(principal))
                .extracting(EnterpriseMemberService.Workspace::enterpriseId).containsExactly(2L);
        db.jdbc.update("UPDATE sys_enterprise SET status=1 WHERE id=2");
        assertThat(members.listWorkspaces(principal)).isEmpty();
        denied(() -> members.listWorkspaces(null), 401);
        denied(() -> members.listWorkspaces(new AuthenticatedUserPrincipal(1)), 401);
        db.jdbc.update("UPDATE sys_user SET auth_version=2 WHERE id=1");
        denied(() -> members.listWorkspaces(principal), 401);
    }

    @Test
    void validMemberMapsAllFieldsAndDifferentEnterpriseHasIndependentMembership() {
        assertThat(owner).isEqualTo(new EnterpriseMemberContext(1, 101, 1, 11L, "EMPLOYEE", 1, 1, 1));
        assertThat(members.requireMember(new AuthenticatedUserPrincipal(1, 1), 2L).memberId()).isEqualTo(201);
        denied(() -> members.requireMember(new AuthenticatedUserPrincipal(2, 1), 2L), 404);
        denied(() -> members.requireMember(null, 1L), 401);
        denied(() -> members.requireMember(() -> "1", 1L), 401);
        denied(() -> members.requireMember(new AuthenticatedUserPrincipal(1), 1L), 401);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void invalidEnterpriseAndResourceIdentifiersAreRejected(Long id) {
        denied(() -> members.requireMember(new AuthenticatedUserPrincipal(1, 1), id), 400);
        denied(() -> resources.requireConversation(owner, id), 400);
        denied(() -> resources.requireDocument(owner, id), 400);
        denied(() -> resources.requireTask(owner, id), 400);
    }

    @Test
    void ownerCanReadOnlyOwnEnterpriseConversationMessagesAndTaskReference() {
        assertThat(resources.requireConversation(owner, 1001L).title()).isEqualTo("本人甲企业会话");
        assertThat(resources.requireMessage(owner, 6001L).content()).isEqualTo("甲企业问题");
        assertThat(resources.requireTask(owner, 9001L).messageId()).isEqualTo(6001);
        for (long id : List.of(1002L, 2001L, 3001L, 4001L, 9999L)) {
            denied(() -> resources.requireConversation(owner, id), 404);
        }
        denied(() -> resources.requireMessage(owner, 6002L), 404);
        denied(() -> resources.requireMessage(owner, 6003L), 404);
        denied(() -> resources.requireTask(owner, 9002L), 404);
        denied(() -> resources.requireTask(owner, 9003L), 404);
        denied(() -> resources.requireTask(owner, 9004L), 404);
    }

    @Test
    void explicitMemberOrCurrentDirectDepartmentGrantIsRequired() throws Exception {
        assertThat(resources.requireSpace(owner, 401L).name()).isEqualTo("成员授权");
        assertThat(resources.requireDocument(owner, 501L).title()).isEqualTo("制度");
        assertThat(resources.requireDocument(owner, 505L).title()).isEqualTo("部门制度");
        for (long id : List.of(502L, 503L, 504L, 506L)) {
            denied(() -> resources.requireDocument(owner, id), 404);
        }
        String serialized = new ObjectMapper().writeValueAsString(resources.requireDocument(owner, 501L));
        assertThat(serialized).doesNotContain("filePath", "不可外露的存储键", "password", "createdBy");
        db.jdbc.update("UPDATE sys_department SET status=1 WHERE id=11");
        denied(() -> resources.requireDocument(owner, 505L), 404);
        assertThat(resources.requireDocument(owner, 501L).id()).isEqualTo(501);
    }

    @Test
    void administratorDoesNotInheritPrivateResources() {
        var admin = members.requireMember(new AuthenticatedUserPrincipal(3, 1), 1L);
        denied(() -> resources.requireConversation(admin, 1001L), 404);
        denied(() -> resources.requireMessage(admin, 6001L), 404);
        denied(() -> resources.requireTask(admin, 9001L), 404);
        denied(() -> resources.requireDocument(admin, 501L), 404);
        assertThat(resources.requireDocument(admin, 505L).id()).isEqualTo(505);
    }

    @Test
    void spacePagesAndTotalsOnlyContainAuthorizedRowsInStableIdOrder() {
        var first = mapper.findSpaces(new Page<>(1, 1), owner);
        assertThat(first.getTotal()).isEqualTo(2);
        assertThat(first.getPages()).isEqualTo(2);
        assertThat(first.getRecords()).extracting(EnterpriseResources.KnowledgeSpace::id).containsExactly(401L);
        assertThat(first.getRecords().get(0).name()).isEqualTo("成员授权");
        assertThat(first.getRecords().get(0).permissionVersion()).isEqualTo(1);
        var second = mapper.findSpaces(new Page<>(2, 1), owner);
        assertThat(second.getTotal()).isEqualTo(2);
        assertThat(second.getRecords()).extracting(EnterpriseResources.KnowledgeSpace::id).containsExactly(404L);
        var beyond = mapper.findSpaces(new Page<>(3, 1), owner);
        assertThat(beyond.getTotal()).isEqualTo(2);
        assertThat(beyond.getRecords()).isEmpty();
    }

    @Test
    void serviceReturnsAuthorizedPageResultIncludingEmptyPageAndAllowedMaximumSize() {
        var second = resources.listSpaces(owner, 2L, 1L);
        assertThat(second.getPageNum()).isEqualTo(2);
        assertThat(second.getPageSize()).isEqualTo(1);
        assertThat(second.getTotal()).isEqualTo(2);
        assertThat(second.getPages()).isEqualTo(2);
        assertThat(second.getRecords()).extracting(EnterpriseResources.KnowledgeSpace::id).containsExactly(404L);
        var beyond = resources.listSpaces(owner, 3L, 1L);
        assertThat(beyond.getPageNum()).isEqualTo(3);
        assertThat(beyond.getTotal()).isEqualTo(2);
        assertThat(beyond.getRecords()).isEmpty();
        var maximum = resources.listSpaces(owner, 1L, 500L);
        assertThat(maximum.getPageSize()).isEqualTo(500);
        assertThat(maximum.getRecords()).extracting(EnterpriseResources.KnowledgeSpace::id)
                .containsExactly(401L, 404L);
    }

    @ParameterizedTest
    @MethodSource("invalidSpacePages")
    void serviceRejectsInvalidSpacePagesBeforeCallingResourceQuery(Long pageNum, Long pageSize) {
        var query = mock(EnterpriseResourceMapper.class);
        var service = new EnterpriseResourceServiceImpl(members, query);
        denied(() -> service.listSpaces(owner, pageNum, pageSize), 400);
        verifyNoInteractions(query);
    }

    private static Stream<Arguments> invalidSpacePages() {
        return Stream.of(
                Arguments.of(null, 10L), Arguments.of(0L, 10L), Arguments.of(-1L, 10L),
                Arguments.of(Long.MIN_VALUE, 10L), Arguments.of(1L, null), Arguments.of(1L, 0L),
                Arguments.of(1L, -1L), Arguments.of(1L, Long.MIN_VALUE), Arguments.of(1L, 501L),
                Arguments.of(1L, Long.MAX_VALUE), Arguments.of(Long.MAX_VALUE, 2L),
                Arguments.of(Long.MAX_VALUE, 500L), Arguments.of(Long.MAX_VALUE / 10 + 2, 10L),
                Arguments.of(Long.MAX_VALUE / 500 + 2, 500L));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 10, 500})
    void serviceAcceptsLargestSafeOffsetsAndBuildsFreshPageWithoutClientSort(long pageSize) {
        long pageNum = pageSize == 1 ? Long.MAX_VALUE : Long.MAX_VALUE / pageSize + 1;
        long expectedOffset = Math.multiplyExact(pageNum - 1, pageSize);
        var query = mock(EnterpriseResourceMapper.class);
        when(query.findSpaces(any(), eq(owner))).thenAnswer(invocation -> {
            Page<EnterpriseResources.KnowledgeSpace> page = invocation.getArgument(0);
            assertThat(page.getCurrent()).isEqualTo(pageNum);
            assertThat(page.getSize()).isEqualTo(pageSize);
            assertThat(page.offset()).isEqualTo(expectedOffset);
            assertThat(page.orders()).isEmpty();
            assertThat(page.searchCount()).isTrue();
            return page;
        });
        var service = new EnterpriseResourceServiceImpl(members, query);
        var result = service.listSpaces(owner, pageNum, pageSize);
        assertThat(result.getPageNum()).isEqualTo(pageNum);
        assertThat(result.getPageSize()).isEqualTo(pageSize);
        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
        verify(query).findSpaces(any(), eq(owner));
    }

    @Test
    void listServiceChecksIdentityBeforeParametersAndDoesNotQueryWithMissingContext() {
        var query = mock(EnterpriseResourceMapper.class);
        var service = new EnterpriseResourceServiceImpl(members, query);
        denied(() -> service.listSpaces(null, 0L, null), 401);
        verifyNoInteractions(query);
    }

    @Test
    void listServiceDoesNotQueryWhenMemberRevalidationFails() {
        var validator = mock(EnterpriseMemberService.class);
        var query = mock(EnterpriseResourceMapper.class);
        var revoked = new BusinessException(com.ragqa.common.ResultCode.NOT_FOUND, "企业不存在或不可访问");
        when(validator.requireCurrent(owner)).thenThrow(revoked);
        var service = new EnterpriseResourceServiceImpl(validator, query);
        assertThatThrownBy(() -> service.listSpaces(owner, 1L, 10L)).isSameAs(revoked);
        verify(validator).requireCurrent(owner);
        verifyNoInteractions(query);
    }

    @Test
    void listServicePropagatesDirectoryFailureWithoutDefaultingToEmptyPage() {
        var validator = mock(EnterpriseMemberService.class);
        var query = mock(EnterpriseResourceMapper.class);
        var failure = new DataAccessResourceFailureException("虚构成员复核故障");
        when(validator.requireCurrent(owner)).thenThrow(failure);
        var service = new EnterpriseResourceServiceImpl(validator, query);
        assertThatThrownBy(() -> service.listSpaces(owner, 1L, 10L)).isSameAs(failure);
        verifyNoInteractions(query);
    }

    @Test
    void listServicePropagatesStorageFailureWithoutRetryOrPretendingNoSpacesExist() {
        var query = mock(EnterpriseResourceMapper.class);
        var failure = new DataAccessResourceFailureException("虚构授权列表故障");
        when(query.findSpaces(any(), eq(owner))).thenThrow(failure);
        var service = new EnterpriseResourceServiceImpl(members, query);
        assertThatThrownBy(() -> service.listSpaces(owner, 1L, 10L)).isSameAs(failure);
        verify(query).findSpaces(any(), eq(owner));
    }

    @Test
    void listSqlStillRejectsRevocationBetweenServiceRevalidationAndActualQuery() {
        var validator = mock(EnterpriseMemberService.class);
        when(validator.requireCurrent(owner)).thenAnswer(invocation -> {
            var checked = members.requireCurrent(owner);
            db.jdbc.update("UPDATE sys_enterprise_member SET status=1 WHERE id=101");
            return checked;
        });
        var service = new EnterpriseResourceServiceImpl(validator, mapper);
        var page = service.listSpaces(owner, 1L, 10L);
        assertThat(page.getTotal()).isZero();
        assertThat(page.getRecords()).isEmpty();
        denied(() -> resources.listSpaces(owner, 1L, 10L), 404);
    }

    @Test
    void spaceListsSeparateEnterprisesAndDoNotGiveAdministratorsImplicitAccess() {
        var otherEnterprise = members.requireMember(new AuthenticatedUserPrincipal(1, 1), 2L);
        var secondList = mapper.findSpaces(new Page<>(1, 10), otherEnterprise);
        assertThat(secondList.getTotal()).isEqualTo(1);
        assertThat(secondList.getRecords()).extracting(EnterpriseResources.KnowledgeSpace::id).containsExactly(403L);
        var admin = members.requireMember(new AuthenticatedUserPrincipal(3, 1), 1L);
        var adminList = mapper.findSpaces(new Page<>(1, 10), admin);
        assertThat(adminList.getTotal()).isEqualTo(1);
        assertThat(adminList.getRecords()).extracting(EnterpriseResources.KnowledgeSpace::id).containsExactly(404L);
        var peer = members.requireMember(new AuthenticatedUserPrincipal(2, 1), 1L);
        var empty = mapper.findSpaces(new Page<>(1, 10), peer);
        assertThat(empty.getTotal()).isZero();
        assertThat(empty.getRecords()).isEmpty();
    }

    @Test
    void overlappingMemberAndDepartmentGrantsDoNotDuplicateRowsOrTotals() {
        db.jdbc.update("""
                INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,department_id,permission)
                VALUES(1,401,NULL,11,'MAINTAIN'),(1,402,101,NULL,'MAINTAIN')
                """);
        var page = mapper.findSpaces(new Page<>(1, 10), owner);
        assertThat(page.getTotal()).isEqualTo(3);
        assertThat(page.getRecords()).extracting(EnterpriseResources.KnowledgeSpace::id)
                .containsExactly(401L, 402L, 404L);
    }

    @Test
    void spaceListRevocationImmediatelyChangesBothRowsAndTotals() {
        assertThat(mapper.findSpaces(new Page<>(1, 10), owner).getTotal()).isEqualTo(2);
        db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE knowledge_space_id=401");
        var remaining = mapper.findSpaces(new Page<>(1, 10), owner);
        assertThat(remaining.getTotal()).isEqualTo(1);
        assertThat(remaining.getRecords()).extracting(EnterpriseResources.KnowledgeSpace::id).containsExactly(404L);
        db.jdbc.update("UPDATE rag_knowledge_space SET status=1 WHERE id=404");
        var empty = mapper.findSpaces(new Page<>(1, 10), owner);
        assertThat(empty.getTotal()).isZero();
        assertThat(empty.getRecords()).isEmpty();
    }

    @Test
    void departmentGrantUsesCurrentMembershipAndActiveDepartmentInsteadOfContextDepartment() {
        // 即使上下文的部门字段过时，SQL只使用数据库内的当前直属部门。
        var alteredDepartment = new EnterpriseMemberContext(1, 101, 1, 22L, "EMPLOYEE", 1, 1, 1);
        assertThat(mapper.findSpaces(new Page<>(1, 10), alteredDepartment).getTotal()).isEqualTo(2);
        db.jdbc.update("UPDATE sys_department SET status=1 WHERE id=11");
        var disabled = mapper.findSpaces(new Page<>(1, 10), owner);
        assertThat(disabled.getTotal()).isEqualTo(1);
        assertThat(disabled.getRecords()).extracting(EnterpriseResources.KnowledgeSpace::id).containsExactly(401L);
        db.jdbc.update("UPDATE sys_department SET status=0 WHERE id=11");
        db.jdbc.update("UPDATE sys_enterprise_member SET department_id=NULL WHERE id=101");
        var moved = mapper.findSpaces(new Page<>(1, 10), owner);
        assertThat(moved.getTotal()).isEqualTo(1);
        assertThat(moved.getRecords()).extracting(EnterpriseResources.KnowledgeSpace::id).containsExactly(401L);
    }

    @Test
    void malformedCrossEnterpriseGrantsCannotExposeForeignSpaceOrItsCount() {
        // 无物理外键不代表信任错误关联；空间、授权与当前成员必须属于同一企业。
        db.jdbc.update("""
                INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,department_id,permission)
                VALUES(1,403,101,NULL,'READ'),(2,402,101,NULL,'READ')
                """);
        var page = mapper.findSpaces(new Page<>(1, 10), owner);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRecords()).extracting(EnterpriseResources.KnowledgeSpace::id).containsExactly(401L, 404L);
    }

    @Test
    void mismatchedMemberOrUserCannotEnumerateSpaceNamesOrTotals() {
        for (var mismatched : List.of(
                new EnterpriseMemberContext(1, 101, 2, 11L, "EMPLOYEE", 1, 1, 1),
                new EnterpriseMemberContext(2, 101, 1, 22L, "EMPLOYEE", 1, 1, 1),
                new EnterpriseMemberContext(1, 999, 1, 11L, "EMPLOYEE", 1, 1, 1))) {
            var page = mapper.findSpaces(new Page<>(1, 10), mismatched);
            assertThat(page.getTotal()).isZero();
            assertThat(page.getRecords()).isEmpty();
        }
    }

    @Test
    void applicationPaginationPluginCapsActualSpaceRowsAtFiveHundred() {
        List<Object[]> spaces = new ArrayList<>();
        List<Object[]> grants = new ArrayList<>();
        for (long id = 1000; id < 1501; id++) {
            spaces.add(new Object[] {id, "分页边界空间" + id});
            grants.add(new Object[] {id});
        }
        db.jdbc.batchUpdate("INSERT INTO rag_knowledge_space(id,enterprise_id,name) VALUES(?,1,?)", spaces);
        db.jdbc.batchUpdate("""
                INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,member_id,permission)
                VALUES(1,?,101,'READ')
                """, grants);
        var page = mapper.findSpaces(new Page<>(1, 501), owner);
        assertThat(page.getSize()).isEqualTo(500);
        assertThat(page.getTotal()).isEqualTo(503);
        assertThat(page.getRecords()).hasSize(500);
        assertThat(page.getRecords().get(0).id()).isEqualTo(401L);
        assertThat(page.getRecords().get(499).id()).isEqualTo(1497L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE sys_enterprise SET status=1 WHERE id=1",
            "UPDATE sys_enterprise_member SET status=1 WHERE id=101",
            "UPDATE sys_enterprise_member SET status=2 WHERE id=101",
            "UPDATE sys_enterprise SET permission_version=2 WHERE id=1",
            "UPDATE sys_enterprise_member SET permission_version=2 WHERE id=101",
            "UPDATE sys_enterprise_member SET role='ADMIN' WHERE id=101",
            "UPDATE sys_user SET status=1 WHERE id=1",
            "UPDATE sys_user SET deleted=1 WHERE id=1",
            "UPDATE sys_user SET auth_version=2 WHERE id=1"
    })
    void revokedStateOrVersionBlocksEvenPreviouslyValidatedContext(String fixedStatement) {
        db.jdbc.update(fixedStatement);
        assertThatThrownBy(() -> members.requireCurrent(owner)).isInstanceOf(BusinessException.class);
        // 即使绕过服务的前置复核，真正读取资源的SQL也必须拒绝旧快照。
        assertThat(mapper.findConversation(owner, 1001)).isNull();
        assertThat(mapper.findMessage(owner, 6001)).isNull();
        assertThat(mapper.findTask(owner, 9001)).isNull();
        assertThat(mapper.findSpace(owner, 401)).isNull();
        assertThat(mapper.findDocument(owner, 501)).isNull();
        var page = mapper.findSpaces(new Page<>(1, 10), owner);
        assertThat(page.getTotal()).isZero();
        assertThat(page.getRecords()).isEmpty();
        assertThatThrownBy(() -> resources.listSpaces(owner, 1L, 10L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void grantAndParentRevocationApplyWithoutCachedAnswerOrExistenceLeak() {
        db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE knowledge_space_id=401");
        denied(() -> resources.requireSpace(owner, 401L), 404);
        denied(() -> resources.requireDocument(owner, 501L), 404);
        db.jdbc.update("UPDATE rag_knowledge_space SET status=1 WHERE id=404");
        denied(() -> resources.requireDocument(owner, 505L), 404);
        db.jdbc.update("UPDATE rag_conversation SET deleted=1 WHERE id=1001");
        denied(() -> resources.requireMessage(owner, 6001L), 404);
        denied(() -> resources.requireTask(owner, 9001L), 404);
    }

    @Test
    void constraintsRejectDuplicateMembershipAndInvalidGrantTargetWithoutForeignKeys() {
        assertThatThrownBy(() -> db.jdbc.update("INSERT INTO sys_enterprise_member(enterprise_id,user_id) VALUES(1,1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.jdbc.update("INSERT INTO rag_knowledge_grant(enterprise_id,knowledge_space_id,permission) VALUES(1,401,'READ')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.jdbc.update("INSERT INTO rag_conversation(user_id,guest_id,enterprise_id) VALUES(1,NULL,1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void everyResourceSqlPinsTenantIdentityAndVersionsUsingBoundParameters() {
        var config = db.factory.getConfiguration();
        for (String method : List.of("findConversation", "findMessage", "findTask", "findSpace", "findDocument")) {
            var parameters = Map.of("scope", owner, "id", 123456789L);
            var sql = config.getMappedStatement(EnterpriseResourceMapper.class.getName() + "." + method).getBoundSql(parameters);
            assertThat(sql.getSql()).contains("u.auth_version = ?", "m.permission_version = ?", "e.permission_version = ?")
                    .doesNotContain("123456789", "${");
            assertThat(sql.getParameterMappings()).extracting(p -> p.getProperty())
                    .contains("scope.enterpriseId", "scope.memberId", "scope.userId", "scope.userAuthVersion",
                            "scope.enterprisePermissionVersion", "scope.memberPermissionVersion", "id");
        }
        var pageSql = config.getMappedStatement(EnterpriseResourceMapper.class.getName() + ".findSpaces")
                .getBoundSql(Map.of("scope", owner, "page", new Page<>(1, 10)));
        assertThat(pageSql.getSql()).contains("EXISTS", "ORDER BY s.id ASC").doesNotContain("${");
        assertThat(pageSql.getParameterMappings()).extracting(p -> p.getProperty())
                .contains("scope.enterpriseId", "scope.memberId", "scope.userId", "scope.role",
                        "scope.userAuthVersion", "scope.enterprisePermissionVersion", "scope.memberPermissionVersion");
    }

    @Test
    void parallelEnterpriseReadsDoNotShareSessionOrContext() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                long enterpriseId = index % 2 + 1;
                calls.add(() -> {
                    try (var session = db.factory.openSession(true)) {
                        var local = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(session.getMapper(SysUserMapper.class)),
                                session.getMapper(EnterpriseDirectoryMapper.class));
                        var member = local.requireMember(new AuthenticatedUserPrincipal(1, 1), enterpriseId);
                        var service = new EnterpriseResourceServiceImpl(local,
                                session.getMapper(EnterpriseResourceMapper.class));
                        var page = service.listSpaces(member, 1L, 10L);
                        var expected = enterpriseId == 1 ? List.of(401L, 404L) : List.of(403L);
                        assertThat(page.getRecords()).extracting(EnterpriseResources.KnowledgeSpace::id)
                                .containsExactlyElementsOf(expected);
                        assertThat(page.getTotal()).isEqualTo(expected.size());
                        return member.enterpriseId();
                    }
                });
            }
            var results = executor.invokeAll(calls, 10, TimeUnit.SECONDS);
            for (int index = 0; index < results.size(); index++) {
                assertThat(results.get(index).get()).isEqualTo(index % 2 + 1L);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(code));
    }
}
