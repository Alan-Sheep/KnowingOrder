package com.ragqa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.UUID;

/** 私有接口请求标识与最小访问记录；不记录URL查询、正文、Cookie、CSRF或口令。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class PrivateApiTraceFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(PrivateApiTraceFilter.class);
    private final ObjectMapper json;

    public PrivateApiTraceFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !path.startsWith("/api/auth/") && !path.startsWith("/api/enterprises/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get("request_id");
        MDC.put("request_id", UUID.randomUUID().toString());
        response.setHeader("X-Request-Id", MDC.get("request_id"));
        response.setHeader("Cache-Control", "no-store");
        long started = System.nanoTime();
        try {
            String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
            boolean upload = path.matches("/api/enterprises/[1-9][0-9]*/knowledge-spaces/[1-9][0-9]*/documents")
                    && request.getContentType() != null
                    && request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data;");
            // 仅文档上传入口放行有界multipart，其余私有POST仍维持16KB上限。
            long maximum = upload ? 2L * 1024 * 1024 : 16L * 1024;
            if ("POST".equals(request.getMethod())
                    && (request.getContentLengthLong() < 0 || request.getContentLengthLong() > maximum)) {
                SecurityJson.failure(json, response, 413, "请求体过大或长度未知，请使用符合入口限制的请求");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            String area = request.getRequestURI().contains("/api/auth/") ? "认证" : "企业资源";
            LOG.info("私有接口完成 area={} method={} http_status={} cost_ms={}", area,
                    request.getMethod(), response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            if (previous == null) {
                MDC.remove("request_id");
            } else {
                MDC.put("request_id", previous);
            }
        }
    }
}
