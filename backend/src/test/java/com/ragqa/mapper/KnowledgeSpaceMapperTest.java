package com.ragqa.mapper;

import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.entity.RagKnowledgeSpace;
import com.ragqa.support.EnterpriseDatabase;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.BeanPropertyRowMapper;

import java.sql.SQLException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 隔离H2执行真实Mapper，验证SQL边界；不代替服务事务、目标MySQL或HTTP验收。 */
class KnowledgeSpaceMapperTest {
    private EnterpriseDatabase db;
    private KnowledgeSpaceMapper mapper;
    private EnterpriseResourceMapper resources;
    private EnterpriseMemberContext admin;

    @BeforeEach
    void setup() throws Exception {
        db = new EnterpriseDatabase();
        db.seed();
        db.factory.getConfiguration().addMapper(KnowledgeSpaceMapper.class);
        mapper = db.session.getMapper(KnowledgeSpaceMapper.class);
        resources = db.session.getMapper(EnterpriseResourceMapper.class);
        admin = db.session.getMapper(EnterpriseDirectoryMapper.class).findActiveMember(3, 1, 1);
    }

    @AfterEach
    void close() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void generatedKeyAndDatabaseDefaultsMatchExistingSpaceSchema() {
        var space = newSpace(1L, "研发知识库");
        // 即使实体携带其他状态，也只能按创建契约写入固定初值。
        space.setStatus(RagKnowledgeSpace.DISABLED);
        space.setPermissionVersion(99L);
        assertThat(mapper.insertSpace(admin, space)).isEqualTo(1);
        assertThat(space.getId()).isNotNull().isPositive();
        var stored = db.jdbc.queryForObject("SELECT * FROM rag_knowledge_space WHERE id = ?",
                BeanPropertyRowMapper.newInstance(RagKnowledgeSpace.class), space.getId());
        assertThat(stored).isNotNull();
        assertThat(stored.getId()).isEqualTo(space.getId());
        assertThat(stored.getEnterpriseId()).isEqualTo(1L);
        assertThat(stored.getName()).isEqualTo("研发知识库");
        assertThat(stored.getStatus()).isEqualTo(RagKnowledgeSpace.ACTIVE);
        assertThat(stored.getPermissionVersion()).isEqualTo(1L);
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isNotNull();
    }

    @Test
    void onlyExplicitCreatorGrantEnablesReadWithoutSharingWithPeersOrDepartments() {
        var space = createSpace();
        assertThat(resources.findSpace(admin, space.getId())).isNull();
        assertThat(mapper.insertCreatorGrant(admin, space.getId())).isEqualTo(1);
        var grant = db.jdbc.queryForMap("SELECT * FROM rag_knowledge_grant WHERE knowledge_space_id = ?",
                space.getId());
        assertThat(((Number) grant.get("enterprise_id")).longValue()).isEqualTo(1L);
        assertThat(((Number) grant.get("member_id")).longValue()).isEqualTo(admin.memberId());
        assertThat(grant.get("department_id")).isNull();
        assertThat(grant.get("permission")).isEqualTo("MAINTAIN");
        assertThat(((Number) grant.get("status")).intValue()).isZero();
        assertThat(resources.findSpace(admin, space.getId()).name()).isEqualTo(space.getName());
        var directory = db.session.getMapper(EnterpriseDirectoryMapper.class);
        // 同企业同部门、同企业其他成员、其他企业成员均不能继承创建者授权。
        for (var other : new EnterpriseMemberContext[] {
                directory.findActiveMember(1, 1, 1), directory.findActiveMember(2, 1, 1),
                directory.findActiveMember(1, 2, 1)}) {
            assertThat(resources.findSpace(other, space.getId())).isNull();
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, 2, 9999})
    void wrongOrMissingEnterpriseCannotCreateSpace(Long enterpriseId) {
        var space = newSpace(enterpriseId, "越界空间");
        assertThat(mapper.insertSpace(admin, space)).isZero();
        assertThat(space.getId()).isNull();
        assertThat(countSpaces()).isEqualTo(4);
    }

    @ParameterizedTest
    @MethodSource("invalidScopes")
    void ordinaryMemberOrMismatchedContextCannotWrite(EnterpriseMemberContext scope) {
        var space = createSpace();
        var attempt = newSpace(scope.enterpriseId(), "不应创建");
        assertThat(mapper.insertSpace(scope, attempt)).isZero();
        assertThat(attempt.getId()).isNull();
        assertThat(mapper.insertCreatorGrant(scope, space.getId())).isZero();
        assertThat(countSpaces()).isEqualTo(5);
        assertThat(countGrants(space.getId())).isZero();
    }

    private static Stream<EnterpriseMemberContext> invalidScopes() {
        return Stream.of(
                new EnterpriseMemberContext(1, 101, 1, 11L, "EMPLOYEE", 1, 1, 1),
                new EnterpriseMemberContext(1, 101, 1, 11L, "ADMIN", 1, 1, 1),
                new EnterpriseMemberContext(2, 103, 3, 11L, "ADMIN", 1, 1, 1),
                new EnterpriseMemberContext(1, 999, 3, 11L, "ADMIN", 1, 1, 1),
                new EnterpriseMemberContext(1, 103, 2, 11L, "ADMIN", 1, 1, 1),
                new EnterpriseMemberContext(1, 103, 3, 11L, "ADMIN", 2, 1, 1),
                new EnterpriseMemberContext(1, 103, 3, 11L, "ADMIN", 1, 2, 1),
                new EnterpriseMemberContext(1, 103, 3, 11L, "ADMIN", 1, 1, 2));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE sys_enterprise SET status = 1 WHERE id = 1",
            "UPDATE sys_enterprise SET permission_version = 2 WHERE id = 1",
            "UPDATE sys_enterprise_member SET status = 1 WHERE id = 103",
            "UPDATE sys_enterprise_member SET status = 2 WHERE id = 103",
            "UPDATE sys_enterprise_member SET role = 'EMPLOYEE' WHERE id = 103",
            "UPDATE sys_enterprise_member SET permission_version = 2 WHERE id = 103",
            "UPDATE sys_user SET status = 1 WHERE id = 3",
            "UPDATE sys_user SET deleted = 1 WHERE id = 3",
            "UPDATE sys_user SET auth_version = 2 WHERE id = 3"
    })
    void revokedStateRejectsBothWritesEvenWithPreviouslyValidContext(String fixedStatement) {
        var space = createSpace();
        db.jdbc.update(fixedStatement);
        assertThat(mapper.insertSpace(admin, newSpace(1L, "旧身份不应创建"))).isZero();
        assertThat(mapper.insertCreatorGrant(admin, space.getId())).isZero();
        assertThat(countSpaces()).isEqualTo(5);
        assertThat(countGrants(space.getId())).isZero();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE, 403, 9999})
    void foreignOrMissingSpaceCannotReceiveCreatorGrant(long spaceId) {
        Integer before = db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_grant", Integer.class);
        assertThat(mapper.insertCreatorGrant(admin, spaceId)).isZero();
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_grant", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void disabledSpaceCannotReceiveCreatorGrant() {
        var space = createSpace();
        db.jdbc.update("UPDATE rag_knowledge_space SET status = 1 WHERE id = ?", space.getId());
        assertThat(mapper.insertCreatorGrant(admin, space.getId())).isZero();
        assertThat(countGrants(space.getId())).isZero();
    }

    @Test
    void duplicateGrantFailsWithoutOverwritingExistingPermission() {
        var space = createSpace();
        assertThat(mapper.insertCreatorGrant(admin, space.getId())).isEqualTo(1);
        db.jdbc.update("UPDATE rag_knowledge_grant SET permission = 'READ' WHERE knowledge_space_id = ?",
                space.getId());
        assertThatThrownBy(() -> mapper.insertCreatorGrant(admin, space.getId()))
                .isInstanceOf(PersistenceException.class).hasRootCauseInstanceOf(SQLException.class);
        assertThat(countGrants(space.getId())).isEqualTo(1);
        assertThat(db.jdbc.queryForObject(
                "SELECT permission FROM rag_knowledge_grant WHERE knowledge_space_id = ?", String.class,
                space.getId())).isEqualTo("READ");
    }

    @Test
    void nameContainingSqlSyntaxIsStoredLiterally() {
        String name = "制度'); DROP TABLE sys_user; --";
        var space = newSpace(1L, name);
        assertThat(mapper.insertSpace(admin, space)).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT name FROM rag_knowledge_space WHERE id = ?", String.class,
                space.getId())).isEqualTo(name);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(3);
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    void databaseRejectsNullOrOverlongNames(String name) {
        assertThatThrownBy(() -> mapper.insertSpace(admin, newSpace(1L, name)))
                .isInstanceOf(PersistenceException.class).hasRootCauseInstanceOf(SQLException.class);
        assertThat(countSpaces()).isEqualTo(4);
    }

    private static Stream<String> invalidNames() {
        return Stream.of(null, "文".repeat(129));
    }

    private RagKnowledgeSpace createSpace() {
        var space = newSpace(1L, "研发知识库");
        assertThat(mapper.insertSpace(admin, space)).isEqualTo(1);
        return space;
    }

    private RagKnowledgeSpace newSpace(Long enterpriseId, String name) {
        var space = new RagKnowledgeSpace();
        space.setEnterpriseId(enterpriseId);
        space.setName(name);
        return space;
    }

    private int countSpaces() {
        return db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_space", Integer.class);
    }

    private int countGrants(long spaceId) {
        return db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_knowledge_grant WHERE knowledge_space_id = ?",
                Integer.class, spaceId);
    }
}
