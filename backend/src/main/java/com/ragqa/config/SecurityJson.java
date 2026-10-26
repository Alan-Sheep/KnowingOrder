package com.ragqa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.Result;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** 过滤器异常不经过MVC异常处理器；仍返回统一JSON，不回显凭据和底层错误。 */
public final class SecurityJson {
    private SecurityJson() {
    }

    public static void failure(ObjectMapper json, HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        write(json, response, Result.fail(status, message));
    }

    public static void write(ObjectMapper json, HttpServletResponse response, Result<?> result) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getWriter(), result);
    }
}
