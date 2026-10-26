package com.ragqa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.entity.SysUser;
import com.ragqa.exception.BusinessException;
import com.ragqa.service.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 不使用真实凭据；验证限流、会话硬过期和身份依赖故障的用户可见行为。 */
class AccountSecurityFilterTest {
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginLimitIsAtomicUnderConcurrencyAndResetsWithFixedWindow() throws Exception {
        Clock clock = mock(Clock.class);
        AtomicLong now = new AtomicLong(100_000);
        when(clock.millis()).thenAnswer(call -> now.get());
        var filter = new LoginGuardFilter(json, clock);
        AtomicInteger passed = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(() -> {
                    var response = new MockHttpServletResponse();
                    filter.doFilter(loginRequest(), response, (request, result) -> passed.incrementAndGet());
                    return response.getStatus();
                });
            }
            var results = executor.invokeAll(calls);
            assertThat(passed).hasValue(10);
            assertThat(results.stream().map(result -> {
                try { return result.get(); } catch (Exception ex) { throw new AssertionError(ex); }
            })).filteredOn(status -> status == 429).hasSize(10);
            now.addAndGet(60_000);
            filter.doFilter(loginRequest(), new MockHttpServletResponse(), (request, result) -> passed.incrementAndGet());
            assertThat(passed).hasValue(11);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void globalLimitAlsoBoundsDistributedAddresses() throws Exception {
        var filter = new LoginGuardFilter(json, Clock.systemUTC(), 1, 2);
        for (int i = 0; i < 3; i++) {
            var request = loginRequest();
            request.setRemoteAddr("192.0.2." + i);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> { });
            assertThat(response.getStatus()).isEqualTo(i < 2 ? 200 : 429);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"query", "oversized", "unicode", "duplicates", "json", "missing", "unknown-length"})
    void invalidCredentialsRequestIsRejectedBeforeAuthentication(String kind) throws Exception {
        var filter = new LoginGuardFilter(json, Clock.systemUTC());
        var request = loginRequest();
        switch (kind) {
            case "query" -> request.setQueryString("password=虚构，不是密钥");
            case "oversized" -> request.setContent(new byte[4097]);
            case "unicode" -> request.setParameter("password", "汉".repeat(25));
            case "duplicates" -> request.setParameter("username", new String[]{"alice", "bob"});
            case "json" -> request.setContentType("application/json");
            case "missing" -> request.removeParameter("password");
            case "unknown-length" -> request.setContent(null);
            default -> throw new AssertionError();
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("无效请求不能进入认证"); });
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 28_800_000, 28_800_001})
    void hardExpiryMissingOrFutureIssueTimeInvalidatesSession(long age) throws Exception {
        var users = mock(CurrentUserService.class);
        long now = 50_000_000;
        var filter = new SessionIdentityFilter(users, json, Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
        var request = authenticatedRequest();
        if (age != 0) {
            request.getSession().setAttribute(SessionIdentityFilter.ISSUED_AT, now - age);
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("过期会话不能通过"); });
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void activeSessionHasBoundedLifetimeAndStorageFailureIsVisible() throws Exception {
        var users = mock(CurrentUserService.class);
        var filter = new SessionIdentityFilter(users, json, Clock.fixed(Instant.ofEpochMilli(50_000_000), ZoneOffset.UTC));
        var request = authenticatedRequest();
        request.getSession().setAttribute(SessionIdentityFilter.ISSUED_AT, 50_000_000L - 28_799_999L);
        AtomicInteger passed = new AtomicInteger();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.incrementAndGet());
        assertThat(passed).hasValue(1);
        when(users.requireUserId(any())).thenThrow(new DataAccessResourceFailureException("虚构内部存储信息"));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("故障不能放行"); });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("身份服务暂不可用").doesNotContain("虚构内部存储信息");
    }

    @Test
    void disabledAccountInvalidatesSessionWithoutReturningInternalReason() throws Exception {
        var users = mock(CurrentUserService.class);
        when(users.requireUserId(any())).thenThrow(new BusinessException(ResultCode.UNAUTHORIZED));
        var filter = new SessionIdentityFilter(users, json, Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC));
        var request = authenticatedRequest();
        request.getSession().setAttribute(SessionIdentityFilter.ISSUED_AT, 900L);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError(); });
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private MockHttpServletRequest loginRequest() {
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent("username=alice&password=test-only-password".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.setParameter("username", "alice");
        request.setParameter("password", "test-only-password");
        return request;
    }

    private MockHttpServletRequest authenticatedRequest() {
        var user = new SysUser();
        user.setId(1L);
        user.setUsername("alice");
        user.setAuthVersion(1L);
        user.setPasswordHash("仅供过滤器单元测试的占位");
        var details = new AccountDetails(user);
        details.eraseCredentials();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(details, null, details.getAuthorities()));
        return new MockHttpServletRequest("GET", "/api/auth/me");
    }
}
