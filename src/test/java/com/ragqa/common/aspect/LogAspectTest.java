package com.ragqa.common.aspect;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ragqa.common.Result;
import com.ragqa.exception.BusinessException;
import com.ragqa.exception.GlobalExceptionHandler;
import com.ragqa.utils.JsonUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link LogAspect} 的行为测试。
 *
 * <p>切片测试不加载切面自动配置，需显式 {@code @Import} 切面与 {@link EnableAspectJAutoProxy}，
 * 否则切面根本没被织入，测试会「假通过」。</p>
 */
@WebMvcTest(controllers = LogAspectTest.TestController.class)
@Import({LogAspectTest.TestController.class, GlobalExceptionHandler.class, LogAspect.class, JsonUtils.class})
@EnableAspectJAutoProxy
class LogAspectTest {

    @Autowired
    private MockMvc mockMvc;

    private Logger aspectLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        aspectLogger = (Logger) LoggerFactory.getLogger(LogAspect.class);
        appender = new ListAppender<>();
        appender.start();
        aspectLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        aspectLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("普通请求：日志记录方法名、耗时与返回值")
    void logsMethodCostAndResult() throws Exception {
        mockMvc.perform(get("/test/aspect/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("ok-data"));

        String logs = collectLogs();
        assertThat(logs).contains("TestController#ok");
        assertThat(logs).contains("完成");
        assertThat(logs).contains("ok-data");
    }

    @Test
    @DisplayName("Servlet 入参被跳过：不会尝试序列化，日志中无序列化失败")
    void skipsServletArguments() throws Exception {
        mockMvc.perform(get("/test/aspect/with-request"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        String logs = collectLogs();
        assertThat(logs).contains("TestController#withRequest");
        // 若未跳过 HttpServletRequest，Jackson 序列化失败会留下这个占位文本
        assertThat(logs).doesNotContain("序列化失败");
    }

    @Test
    @DisplayName("业务异常：切面不重复记录，异常原样抛出，由全局异常处理器转成统一响应")
    void businessExceptionIsNotLoggedByAspect() throws Exception {
        mockMvc.perform(get("/test/aspect/biz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("文档不存在"));

        // 响应仍是业务体，说明异常没有被切面吞掉；切面日志里则不应出现任何记录
        assertThat(appender.list).as("切面对业务异常不输出任何日志").isEmpty();
    }

    @Test
    @DisplayName("意外异常：切面补一行 warn（含耗时与入参），异常仍原样抛出")
    void unexpectedExceptionIsLoggedByAspect() throws Exception {
        mockMvc.perform(get("/test/aspect/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));

        String logs = collectLogs();
        assertThat(logs).contains("TestController#boom");
        assertThat(logs).contains("异常");
        assertThat(logs).contains("IllegalStateException");
        assertThat(logs).contains("耗时=");
    }

    /** 汇总本次用例捕获到的全部日志文本 */
    private String collectLogs() {
        List<ILoggingEvent> events = List.copyOf(appender.list);
        return events.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    /** 仅供本测试使用的接口 */
    @RestController
    public static class TestController {

        @GetMapping("/test/aspect/ok")
        public Result<String> ok() {
            return Result.ok("ok-data");
        }

        @GetMapping("/test/aspect/with-request")
        public Result<String> withRequest(HttpServletRequest request) {
            return Result.ok(request.getRequestURI());
        }

        @GetMapping("/test/aspect/biz")
        public Result<Void> biz() {
            throw new BusinessException("文档不存在");
        }

        @GetMapping("/test/aspect/boom")
        public Result<Void> boom() {
            throw new IllegalStateException("意外故障");
        }
    }
}
