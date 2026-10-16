package com.ragqa.common.aspect;

import com.ragqa.exception.BusinessException;
import com.ragqa.utils.JsonUtils;
import com.ragqa.utils.StrUtils;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Controller 请求日志切面：统一记录方法、入参、返回值、耗时与意外异常。
 *
 * <p>切所有 {@code @RestController} 下的方法。异常一律原样抛出，
 * 继续交给 {@link com.ragqa.exception.GlobalExceptionHandler} 处理，切面只观察不干预。
 * 其中业务异常（{@link BusinessException}）已由处理器记录，切面不重复记录；
 * 只有未预期的异常才在此补一行带耗时与入参的 WARN。</p>
 *
 * <p>当前切点只覆盖 {@code @RestController}；若以后出现 {@code @Controller + @ResponseBody}
 * 写法，需把切点改为 {@code @within(org.springframework.stereotype.Controller)}。</p>
 *
 * <p>入参序列化复用 {@link JsonUtils}（即容器里的 ObjectMapper），因此
 * {@code @JsonIgnore} 标记的敏感字段不会进入日志。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class LogAspect {

    /** 单条日志中入参 / 返回值的最大字符数，避免大对象刷屏 */
    private static final int MAX_LOG_LENGTH = 500;

    private final JsonUtils jsonUtils;

    @Around("@within(org.springframework.web.bind.annotation.RestController)")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        String method = joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "#" + joinPoint.getSignature().getName();
        String args = describeArgs(joinPoint.getArgs());

        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            long costMillis = (System.nanoTime() - start) / 1_000_000;
            log.info("[{}] 完成 耗时={}ms 入参={} 返回={}", method, costMillis, args, describe(result));
            return result;
        } catch (BusinessException e) {
            // 业务异常由 GlobalExceptionHandler 统一记录（含错误码与堆栈），这里放行不重复记录
            throw e;
        } catch (Throwable e) {
            long costMillis = (System.nanoTime() - start) / 1_000_000;
            // 意外异常：只记类型与消息（附耗时和入参便于定位），堆栈由 GlobalExceptionHandler 记录
            log.warn("[{}] 异常 耗时={}ms 入参={} 异常={}: {}", method, costMillis, args,
                    e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
    }

    /**
     * 序列化入参。跳过无法或不该序列化的参数：Servlet 请求/响应、上传文件、绑定结果。
     * 全部被跳过时返回 "-"。
     */
    private String describeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "-";
        }
        List<String> printable = new ArrayList<>(args.length);
        for (Object arg : args) {
            if (arg == null) {
                printable.add("null");
            } else if (isSkippable(arg)) {
                printable.add("<" + arg.getClass().getSimpleName() + ">");
            } else {
                printable.add(jsonUtils.toJson(arg));
            }
        }
        return StrUtils.truncate(String.join(", ", printable), MAX_LOG_LENGTH);
    }

    private String describe(Object result) {
        return result == null ? "null" : StrUtils.truncate(jsonUtils.toJson(result), MAX_LOG_LENGTH);
    }

    /** 判断该入参是否应跳过序列化 */
    private boolean isSkippable(Object arg) {
        return arg instanceof ServletRequest
                || arg instanceof ServletResponse
                || arg instanceof MultipartFile
                || arg instanceof Errors;
    }
}
