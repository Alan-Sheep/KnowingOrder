package com.ragqa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.exception.BusinessException;
import com.ragqa.service.CurrentUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;

/** 每次登录态请求都复核账号与认证版本；超时、停用、改密撤销后不沿用旧会话。 */
public final class SessionIdentityFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(SessionIdentityFilter.class);
    public static final String ISSUED_AT = SessionIdentityFilter.class.getName() + ".issuedAt";
    private static final long HARD_LIMIT_MS = 8 * 60 * 60 * 1000L;
    private final CurrentUserService users;
    private final ObjectMapper json;
    private final Clock clock;

    public SessionIdentityFilter(CurrentUserService users, ObjectMapper json, Clock clock) {
        this.users = users;
        this.json = json;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountDetails account) {
            var session = request.getSession(false);
            Object issued = session == null ? null : session.getAttribute(ISSUED_AT);
            if (!(issued instanceof Long started) || started > clock.millis()
                    || clock.millis() - started >= HARD_LIMIT_MS || account.getIdentity().authVersion() <= 0) {
                reject(request, response);
                return;
            }
            try {
                users.requireUserId(account.getIdentity());
            } catch (BusinessException ex) {
                reject(request, response);
                return;
            } catch (DataAccessException ex) {
                // 存储故障不冒充退出成功或放行私有资源，也不回显SQL与驱动错误。
                LOG.warn("身份复核失败 code=503 type={}", ex.getClass().getSimpleName());
                SecurityJson.failure(json, response, 503, "身份服务暂不可用，请稍后重试");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityJson.failure(json, response, 401, "请先登录或登录已失效");
    }
}
