package com.ragqa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** 单节点登录保护：先限制请求大小和尝试频率，再进行有成本的密码散列校验。 */
public final class LoginGuardFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    private final Clock clock;
    private final int perAddressLimit;
    private final int globalLimit;
    private final Map<String, Window> attempts = new HashMap<>();
    private Window global = new Window(0, 0);

    public LoginGuardFilter(ObjectMapper json, Clock clock) {
        this(json, clock, 10, 120);
    }

    public LoginGuardFilter(ObjectMapper json, Clock clock, int perAddressLimit, int globalLimit) {
        if (perAddressLimit <= 0 || globalLimit < perAddressLimit || globalLimit > 100_000) {
            throw new IllegalArgumentException("登录限额配置无效");
        }
        this.json = json;
        this.clock = clock;
        this.perAddressLimit = perAddressLimit;
        this.globalLimit = globalLimit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod())
                && "/api/auth/login".equals(UrlPathHelper.defaultInstance.getPathWithinApplication(request)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!allow(request.getRemoteAddr())) {
            response.setHeader("Retry-After", "60");
            SecurityJson.failure(json, response, 429, "登录尝试过于频繁，请稍后再试");
            return;
        }
        // 不允许查询串携带密码；明确长度避免无界表单解析。未知长度请由客户端发送固定长度表单。
        if (request.getQueryString() != null || request.getContentLengthLong() < 0
                || request.getContentLengthLong() > 4096 || request.getContentType() == null
                || !request.getContentType().startsWith("application/x-www-form-urlencoded")) {
            SecurityJson.failure(json, response, 400, "请使用不超过4KB的登录表单");
            return;
        }
        String username = request.getParameter("username");
        String password = request.getParameter("password");
        if (request.getParameterValues("username") == null || request.getParameterValues("username").length != 1
                || request.getParameterValues("password") == null || request.getParameterValues("password").length != 1
                || username == null || username.isBlank() || username.length() > 64
                || password == null || password.isEmpty() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            SecurityJson.failure(json, response, 400, "账号或密码格式不正确");
            return;
        }
        chain.doFilter(request, response);
    }

    private synchronized boolean allow(String address) {
        long now = clock.millis();
        attempts.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= 60_000);
        if (now - global.startedAt() >= 60_000) {
            global = new Window(now, 0);
        }
        Window local = attempts.getOrDefault(address, new Window(now, 0));
        if (local.count() >= perAddressLimit || global.count() >= globalLimit
                || (!attempts.containsKey(address) && attempts.size() >= 4096)) {
            return false;
        }
        attempts.put(address, new Window(local.startedAt(), local.count() + 1));
        global = new Window(global.startedAt(), global.count() + 1);
        return true;
    }

    private record Window(long startedAt, int count) {
    }
}
