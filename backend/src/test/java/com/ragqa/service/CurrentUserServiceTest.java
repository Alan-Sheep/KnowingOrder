package com.ragqa.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.ragqa.common.Constants;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.entity.SysUser;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.SysUserMapper;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 使用模拟认证主体和Mapper验证身份映射；真实生成SQL，不建立数据库连接。 */
class CurrentUserServiceTest {

    private static Configuration configuration;
    private final SysUserMapper mapper = mock(SysUserMapper.class);
    private final CurrentUserService service = new CurrentUserServiceImpl(mapper);

    @BeforeAll
    static void initializeRealMapping() throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        DataSource dataSource = mock(DataSource.class);
        factory.setDataSource(dataSource);
        configuration = factory.getObject().getConfiguration();
        configuration.addMapper(SysUserMapper.class);
        verifyNoInteractions(dataSource);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 77L, Long.MAX_VALUE})
    @DisplayName("按认证主体的稳定主键查询正常未删除账号，不加载用户名或密码")
    void identityUsesPinnedBoundIdAndOnlySelectsId(long userId) {
        doAnswer(invocation -> {
            assertIdentityQuery(invocation.getArgument(0), userId);
            return user(userId);
        }).when(mapper).selectOne(any());

        assertThat(service.requireUserId(new AuthenticatedUserPrincipal(userId))).isEqualTo(userId);
    }

    @Test
    @DisplayName("没有认证主体时立即拒绝，不查询账号")
    void missingPrincipalIsRejectedBeforeQuery() {
        assertUnauthorized(() -> service.requireUserId(null));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("仅有名称的普通Principal不能被当作已验证的用户主键")
    void untypedPrincipalCannotBecomeAnAuthenticatedUser() {
        when(mapper.selectOne(any())).thenReturn(user(77L));
        assertUnauthorized(() -> service.requireUserId(() -> "77"));
        verifyNoInteractions(mapper);
    }

    static Stream<String> invalidNames() {
        return Stream.of(null, "", " \t", "a".repeat(65), "😀".repeat(65));
    }

    @ParameterizedTest
    @MethodSource("invalidNames")
    @DisplayName("普通Principal无论名称是什么都不能建立身份")
    void untypedPrincipalNamesNeverQuery(String username) {
        assertUnauthorized(() -> service.requireUserId(() -> username));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    @DisplayName("无效主键不能构造本项目的认证主体")
    void invalidPrincipalIdCannotBeConstructed(long userId) {
        assertThatThrownBy(() -> new AuthenticatedUserPrincipal(userId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("认证用户编号必须为正整数");
    }

    @Test
    @DisplayName("没有正常未删除的匹配账号时返回相同的安全身份提示")
    void unavailableAccountHasOneSafeFailure() {
        doAnswer(invocation -> {
            assertIdentityQuery(invocation.getArgument(0), 77L);
            return null;
        }).when(mapper).selectOne(any());

        assertUnauthorized(() -> service.requireUserId(new AuthenticatedUserPrincipal(77L)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    @DisplayName("异常账号编号不能变成有效身份")
    void invalidStoredIdIsRejected(Long userId) {
        when(mapper.selectOne(any())).thenReturn(user(userId));
        assertUnauthorized(() -> service.requireUserId(new AuthenticatedUserPrincipal(77L)));
    }

    @Test
    @DisplayName("数据层返回其他账号编号时拒绝，不改变已绑定身份")
    void mismatchedStoredIdCannotReplacePinnedIdentity() {
        when(mapper.selectOne(any())).thenReturn(user(88L));
        assertUnauthorized(() -> service.requireUserId(new AuthenticatedUserPrincipal(77L)));
    }

    @Test
    @DisplayName("数据库故障不能伪装成未登录，也不能授予默认用户")
    void storageFailureIsNotConvertedIntoAnonymousIdentity() {
        var failure = new DataAccessResourceFailureException("虚构身份存储故障");
        when(mapper.selectOne(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.requireUserId(new AuthenticatedUserPrincipal(77L))).isSameAs(failure);
    }

    @Test
    @DisplayName("不读取不受支持主体的名称，也不据此探测账号")
    void untypedPrincipalNameIsNeverRead() {
        AtomicInteger reads = new AtomicInteger();
        assertUnauthorized(() -> service.requireUserId(() -> {
            reads.incrementAndGet();
            return "77";
        }));
        assertThat(reads).hasValue(0);
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("20组并发解析不共享认证主体或用户编号")
    void parallelResolutionsKeepIdentitiesIsolated() throws Exception {
        doAnswer(invocation -> {
            return user((Long) boundParameters(invocation.getArgument(0)).get(0));
        }).when(mapper).selectOne(any());
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Long>> calls = new ArrayList<>();
            for (long userId = 1; userId <= 20; userId++) {
                var principal = new AuthenticatedUserPrincipal(userId);
                calls.add(() -> service.requireUserId(principal));
            }
            var results = executor.invokeAll(calls, 5, TimeUnit.SECONDS);
            for (int index = 0; index < results.size(); index++) {
                assertThat(results.get(index).get()).isEqualTo(index + 1L);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static SysUser user(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        return user;
    }

    private static void assertUnauthorized(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo(401);
            assertThat(exception.getMessage()).isEqualTo("请先登录或登录已失效");
        });
    }

    private static void assertIdentityQuery(Wrapper<?> wrapper, long userId) {
        String sql = boundSql(wrapper).getSql().replaceAll("\\s+", " ").strip();
        assertThat(sql).containsPattern("^SELECT\\s+id\\s+FROM\\s+sys_user\\b")
                .containsPattern("WHERE\\s+deleted\\s*=\\s*0")
                .containsPattern("id\\s*=\\s*\\?\\s+AND\\s+status\\s*=\\s*\\?")
                .doesNotContain("username", "password_hash", "email", "nickname", " OR ");
        assertThat(boundParameters(wrapper)).containsExactly(userId, Constants.UserStatus.NORMAL);
    }

    private static BoundSql boundSql(Wrapper<?> wrapper) {
        return configuration.getMappedStatement(SysUserMapper.class.getName() + ".selectList")
                .getBoundSql(Map.of("ew", wrapper));
    }

    private static List<Object> boundParameters(Wrapper<?> wrapper) {
        var parameters = configuration.newMetaObject(Map.of("ew", wrapper));
        return boundSql(wrapper).getParameterMappings().stream()
                .map(mapping -> parameters.getValue(mapping.getProperty())).toList();
    }
}
