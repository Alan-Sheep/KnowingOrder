package com.ragqa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * 问答和商品查询开发闭环保护：仅接受直连回环地址，限制 JSON 请求大小并关联请求编号。
 * 不信任代理转发头；这不是用户鉴权，禁止作为公开服务的访问控制方案。
 */
@Component
@Profile("dev")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LocalChatFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_BYTES = 8192;
    private final ObjectMapper mapper;

    public LocalChatFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 使用解码并移除路径参数后的应用内路径，避免编码或分号路径绕过保护。
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !path.startsWith("/api/chat") && !path.startsWith("/api/products");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String oldId = MDC.get("request_id");
        String id = UUID.randomUUID().toString();
        MDC.put("request_id", id);
        response.setHeader("X-Request-Id", id);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            if (!Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())
                    || request.getHeader("Forwarded") != null || request.getHeader("X-Forwarded-For") != null) {
                reject(response, 403, "当前问答入口仅供本机开发验证，暂未开放远程访问");
                return;
            }
            if (!"POST".equals(request.getMethod())) {
                chain.doFilter(request, response);
                return;
            }
            if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                reject(response, 413, "请求体过大，请缩短问题后重试");
                return;
            }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                reject(response, 413, "请求体过大，请缩短问题后重试");
                return;
            }
            chain.doFilter(new BufferedRequest(request, body), response);
        } finally {
            if (oldId == null) {
                MDC.remove("request_id");
            } else {
                MDC.put("request_id", oldId);
            }
        }
    }

    private void reject(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), Result.fail(code, message));
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] data, int offset, int length) { return input.read(data, offset, length); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("开发同步入口不支持异步请求体读取");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
