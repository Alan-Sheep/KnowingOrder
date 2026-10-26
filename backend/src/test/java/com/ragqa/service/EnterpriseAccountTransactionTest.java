package com.ragqa.service;

import com.ragqa.domain.dto.NewEmployeeRequest;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.EnterpriseAccountMapper;
import com.ragqa.mapper.EnterpriseDirectoryMapper;
import com.ragqa.mapper.SysUserMapper;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseAccountServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实事务代理+SQL验证首建，使用独立空内存库，不改变业务库。 */
class EnterpriseAccountTransactionTest {
    private static final NewEmployeeRequest ADMIN = new NewEmployeeRequest("initial_admin", "Only-in-test#2026", null);

    @Test
    void firstAdminIsCreatedOnceWithPasswordHashAndMembership() throws Exception {
        try (var db = new EnterpriseDatabase()) {
            var accounts = accounts(db);
            var created = accounts.bootstrapFirstAdmin("试点企业", ADMIN);
            assertThat(created.enterpriseId()).isEqualTo(1L);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_enterprise", Integer.class)).isEqualTo(1);
            assertThat(db.jdbc.queryForObject("SELECT role FROM sys_enterprise_member WHERE id=?", String.class, created.memberId())).isEqualTo("ADMIN");
            String hash = db.jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE id=?", String.class, created.userId());
            assertThat(new BCryptPasswordEncoder().matches(ADMIN.password(), hash)).isTrue();
            assertThatThrownBy(() -> accounts.bootstrapFirstAdmin("第二家企业", ADMIN)).isInstanceOf(BusinessException.class);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void invalidOrDuplicateAccountRollsBackEnterpriseWithoutAdoptingHistoricalUsers() throws Exception {
        try (var db = new EnterpriseDatabase()) {
            var accounts = accounts(db);
            assertThatThrownBy(() -> accounts.bootstrapFirstAdmin("试点企业", new NewEmployeeRequest("admin", "short", null)))
                    .isInstanceOf(BusinessException.class);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_enterprise", Integer.class)).isZero();
            db.jdbc.update("INSERT INTO sys_user(username,password_hash) VALUES('initial_admin','旧账号测试占位')");
            assertThatThrownBy(() -> accounts.bootstrapFirstAdmin("试点企业", ADMIN)).isInstanceOf(BusinessException.class);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_enterprise", Integer.class)).isZero();
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_enterprise_member", Integer.class)).isZero();
        }
    }

    @Test
    void simultaneousFirstSetupCannotCreateTwoEnterprisesOrAdministrators() throws Exception {
        try (var db = new EnterpriseDatabase()) {
            var accounts = accounts(db);
            var executor = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> attempt = () -> {
                    try {
                        accounts.bootstrapFirstAdmin("试点企业", ADMIN);
                        return true;
                    } catch (BusinessException | org.springframework.dao.DataAccessException expected) {
                        return false;
                    }
                };
                var results = executor.invokeAll(List.of(attempt, attempt));
                assertThat(List.of(results.get(0).get(), results.get(1).get())).containsExactlyInAnyOrder(true, false);
                assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_enterprise", Integer.class)).isEqualTo(1);
                assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(1);
                assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_enterprise_member", Integer.class)).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private EnterpriseAccountService accounts(EnterpriseDatabase db) {
        var sql = new SqlSessionTemplate(db.factory);
        var users = sql.getMapper(SysUserMapper.class);
        var directory = sql.getMapper(EnterpriseDirectoryMapper.class);
        var current = new CurrentUserServiceImpl(users);
        var target = new EnterpriseAccountServiceImpl(new EnterpriseMemberServiceImpl(current, directory), current,
                directory, sql.getMapper(EnterpriseAccountMapper.class), users, new BCryptPasswordEncoder(4));
        var transaction = new TransactionInterceptor();
        transaction.setTransactionManager(new DataSourceTransactionManager(db.dataSource));
        transaction.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(transaction);
        return (EnterpriseAccountService) proxy.getProxy();
    }
}
