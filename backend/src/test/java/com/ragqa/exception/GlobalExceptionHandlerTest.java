package com.ragqa.exception;

import com.ragqa.common.Result;
import com.ragqa.common.ResultCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证全局异常处理器把各类异常转换为统一 {@link Result} 结构。
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
@Import(GlobalExceptionHandlerTest.TestController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void okReturnsSuccessResult() throws Exception {
        mockMvc.perform(get("/test/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data").value("pong"))
                .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void businessExceptionReturns200WithBizCode() throws Exception {
        mockMvc.perform(get("/test/biz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.BIZ_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("文档不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void invalidBodyReturns400() throws Exception {
        mockMvc.perform(post("/test/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("问题不能为空"));
    }

    @Test
    void missingParamReturns400() throws Exception {
        mockMvc.perform(get("/test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));
    }

    @Test
    void unknownExceptionHidesDetail() throws Exception {
        mockMvc.perform(get("/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ResultCode.SYSTEM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value(ResultCode.SYSTEM_ERROR.getMessage()));
    }

    @Test
    void uploadTooLargeReturns413() throws Exception {
        mockMvc.perform(get("/test/too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(ResultCode.FILE_TOO_LARGE.getCode()))
                .andExpect(jsonPath("$.message").value(ResultCode.FILE_TOO_LARGE.getMessage()));
    }

    @Test
    void taskRejectedReturns503() throws Exception {
        mockMvc.perform(get("/test/rejected"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ResultCode.SERVICE_BUSY.getCode()));
    }

    @Test
    void unknownPathReturns404() throws Exception {
        mockMvc.perform(get("/not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ResultCode.NOT_FOUND.getCode()));
    }

    /** 仅供本测试使用的接口 */
    @RestController
    static class TestController {

        @GetMapping("/test/ok")
        Result<String> ok() {
            return Result.ok("pong");
        }

        @GetMapping("/test/biz")
        Result<Void> biz() {
            throw new BusinessException("文档不存在");
        }

        @PostMapping("/test/valid")
        Result<Void> valid(@Valid @RequestBody AskRequest request) {
            return Result.ok();
        }

        @GetMapping("/test/param")
        Result<String> param(@RequestParam String id) {
            return Result.ok(id);
        }

        @GetMapping("/test/error")
        Result<Void> error() {
            throw new IllegalStateException("内部细节不应外泄");
        }

        @GetMapping("/test/too-large")
        Result<Void> tooLarge() {
            throw new MaxUploadSizeExceededException(50L * 1024 * 1024);
        }

        @GetMapping("/test/rejected")
        Result<Void> rejected() {
            throw new TaskRejectedException("线程池已满");
        }
    }

    @Data
    static class AskRequest {
        @NotBlank(message = "问题不能为空")
        private String question;
    }
}
