package com.ragqa.service;

import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.*;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** 真实Mapper与Spring事务的隔离H2验证，不替代MySQL锁或生产角色验收。 */
class EnterpriseOrganizationTest {
    private final AuthenticatedUserPrincipal admin = new AuthenticatedUserPrincipal(3, 1);
    private EnterpriseDatabase db;
    private EnterpriseOrganizationMapper mapper;
    private EnterpriseOrganizationService service;
    private EnterpriseMemberService memberService;
    private EnterpriseDirectoryMapper directory;
    @BeforeEach void setUp() throws Exception {
        db = new EnterpriseDatabase(); db.seed();
        db.factory.getConfiguration().addMapper(EnterpriseOrganizationMapper.class);
        var sql = new SqlSessionTemplate(db.factory);
        directory = sql.getMapper(EnterpriseDirectoryMapper.class);
        memberService = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)), directory);
        mapper = spy(sql.getMapper(EnterpriseOrganizationMapper.class));
        service = new EnterpriseOrganizationService(memberService, directory, mapper, new DataSourceTransactionManager(db.dataSource));
    }
    @AfterEach void close() throws Exception { db.close(); }
    private void code(Runnable work, int code) { assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code)); }
    private long revision() { return db.jdbc.queryForObject("SELECT permission_version FROM sys_enterprise WHERE id=1", Long.class); }

    @Test void pagesAreEnterpriseScopedAndNeverExposeHashes() {
        var page = service.departments(admin, 1, 1, 1);
        assertThat(page.page().getTotal()).isEqualTo(1); assertThat(page.page().getRecords().get(0).id()).isEqualTo(11);
        var members = service.members(admin, 1, 1, 2).page();
        assertThat(members.getTotal()).isEqualTo(3); assertThat(members.getRecords()).hasSize(2);
        assertThat(service.members(admin, 1, 2, 2).page().getRecords()).extracting(EnterpriseOrganizationMapper.Member::id).containsExactly(103L);
        assertThat(service.departments(admin, 1, 2, 20).page().getRecords()).isEmpty();
    }
    @Test void nonAdminCrossEnterpriseAndRevokedIdentityCannotReadOrWrite() {
        var employee = new AuthenticatedUserPrincipal(1, 1);
        code(() -> service.departments(employee, 1, 1, 20), 404);
        code(() -> service.create(employee, 1, 1, "越权"), 404);
        code(() -> service.members(admin, 2, 1, 20), 404);
        code(() -> service.departments(new AuthenticatedUserPrincipal(3, 2), 1, 1, 20), 401);
        db.jdbc.update("UPDATE sys_enterprise_member SET status=1 WHERE id=103");
        code(() -> service.members(admin, 1, 1, 20), 404);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\n", "部门\n名称", "部门\u0000", "部门\u2028名称"})
    void invalidNamesCannotWrite(String name) { code(() -> service.create(admin, 1, 1, name), 400); assertThat(revision()).isEqualTo(1); }
    @Test void lengthAndPaginationAreBounded() {
        code(() -> service.create(admin, 1, 1, "字".repeat(129)), 400);
        code(() -> service.departments(admin, 1, 0, 20), 400);
        code(() -> service.members(admin, 1, 1, 101), 400);
        code(() -> service.members(admin, 1, Long.MAX_VALUE, 20), 400);
        code(() -> service.create(admin, 1, Long.MAX_VALUE, "新部门"), 400);
    }
    @Test void createRenameDisableEnableAndNoOpUseExpectedEnterpriseVersion() {
        var created = service.create(admin, 1, 1, "  新部门  ");
        long id = created.department().id(); assertThat(created.department().name()).isEqualTo("新部门");
        assertThat(created.enterprisePermissionVersion()).isEqualTo(2);
        code(() -> service.create(admin, 1, 1, "旧版本"), 409);
        code(() -> service.change(admin, 1, 22, 2, "跨企业", 0), 404);
        assertThat(service.change(admin, 1, id, 2, "新部门", 0).enterprisePermissionVersion()).isEqualTo(2);
        assertThat(service.change(admin, 1, id, 2, "改名", 1).enterprisePermissionVersion()).isEqualTo(3);
        assertThat(service.change(admin, 1, id, 3, "改名", 0).department().status()).isZero();
        assertThat(revision()).isEqualTo(4);
    }
    @Test void sameNameIsRejectedAndSqlCharactersAreBoundAsData() {
        code(() -> service.create(admin, 1, 1, "研发"), 409);
        String text = "财务' OR 1=1 -- <script>";
        var created = service.create(admin, 1, 1, text);
        assertThat(created.department().name()).isEqualTo(text);
        assertThat(service.departments(admin, 1, 1, 20).page().getTotal()).isEqualTo(2);
        code(() -> service.change(admin, 1, created.department().id(), 2, "研发", 0), 409);
    }
    @Test void occupiedDepartmentAndActiveGrantPreventDisableWithoutPartialWrite() {
        code(() -> service.change(admin, 1, 11, 1, "不应改名", 1), 409);
        assertThat(db.jdbc.queryForObject("SELECT name FROM sys_department WHERE id=11", String.class)).isEqualTo("研发");
        db.jdbc.update("UPDATE sys_enterprise_member SET department_id=NULL WHERE enterprise_id=1");
        code(() -> service.change(admin, 1, 11, 1, "研发", 1), 409);
        db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE department_id=11");
        assertThat(service.change(admin, 1, 11, 1, "研发", 1).department().status()).isEqualTo(1);
    }
    @Test void memberMoveRevokesOldSnapshotAndCannotEnterDisabledOrForeignDepartment() {
        var context = memberService.requireMember(new AuthenticatedUserPrincipal(1, 1), 1L);
        code(() -> service.changeMember(admin, 1, 101, 1, 22L, 0), 404);
        db.jdbc.update("INSERT INTO sys_department(id,enterprise_id,name,status) VALUES(33,1,'关闭',1)");
        code(() -> service.changeMember(admin, 1, 101, 1, 33L, 0), 404);
        var changed = service.changeMember(admin, 1, 101, 1, null, 0);
        assertThat(changed.departmentId()).isNull(); assertThat(changed.permissionVersion()).isEqualTo(2);
        code(() -> memberService.requireCurrent(context), 404);
        code(() -> service.changeMember(admin, 1, 101, 1, 11L, 0), 409);
        assertThat(service.changeMember(admin, 1, 101, 2, 11L, 2).status()).isEqualTo(2);
        code(() -> memberService.requireMember(new AuthenticatedUserPrincipal(1, 1), 1L), 404);
    }
    @Test void memberNoOpAdminProtectionAndTargetScope() {
        assertThat(service.changeMember(admin, 1, 102, 1, null, 0).permissionVersion()).isEqualTo(1);
        code(() -> service.changeMember(admin, 1, 103, 1, null, 1), 404);
        code(() -> service.changeMember(admin, 1, 201, 1, null, 1), 404);
        code(() -> service.changeMember(admin, 1, 102, 1, null, 3), 400);
        code(() -> service.changeMember(admin, 1, 102, 0, null, 0), 400);
    }
    @Test void failedRevisionAdvanceRollsBackDepartmentInsertAndUpdate() {
        doReturn(0).when(mapper).advanceEnterprise(anyLong(), anyLong());
        code(() -> service.create(admin, 1, 1, "必须回滚"), 409);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_department WHERE enterprise_id=1", Integer.class)).isEqualTo(1);
        code(() -> service.change(admin, 1, 11, 1, "不得残留", 0), 409);
        assertThat(db.jdbc.queryForObject("SELECT name FROM sys_department WHERE id=11", String.class)).isEqualTo("研发");
    }
    @Test void concurrentSameVersionMemberWritesAllowOnlyOneWinner() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Callable<Integer> work = () -> { start.await(); try { service.changeMember(admin, 1, 102, 1, 11L, 1); return 0; }
                catch (BusinessException ex) { return ex.getCode(); } };
            var a = executor.submit(work); var b = executor.submit(work); start.countDown();
            assertThat(java.util.List.of(a.get(8, TimeUnit.SECONDS), b.get(8, TimeUnit.SECONDS))).containsExactlyInAnyOrder(0, 409);
            assertThat(db.jdbc.queryForObject("SELECT permission_version FROM sys_enterprise_member WHERE id=102", Long.class)).isEqualTo(2);
        } finally { executor.shutdownNow(); }
    }
}
