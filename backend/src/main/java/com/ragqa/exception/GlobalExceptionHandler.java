package com.ragqa.exception;

import com.ragqa.common.Result;
import com.ragqa.common.ResultCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：把所有异常统一转换为 {@link Result}。
 *
 * <p>HTTP 状态码策略：业务异常返回 200（由 body 中 code 区分）；
 * 框架层错误返回与语义一致的 4xx/5xx，便于网关、监控按状态码统计。</p>
 *
 * <p>日志策略：业务异常只在本类记录一次，WARN 级别并带错误码与堆栈（LogAspect 对业务异常不再重复记录）；
 * 其余 4xx 记 WARN 不打堆栈；未知异常记 ERROR 带堆栈，且不把异常信息回传给前端。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusiness(BusinessException e) {
        // 末尾传入异常对象，SLF4J 会把它当作堆栈输出而不是占位符参数
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage(), e);
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** @RequestBody @Valid 校验失败（MethodArgumentNotValidException 是 BindException 子类），以及表单/查询对象绑定失败 */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBind(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return paramError(message);
    }

    /** 类上加 @Validated 时，方法参数（@RequestParam/@PathVariable）校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        return paramError(message);
    }

    /** Spring 6.1 内置方法参数校验失败（类上未加 @Validated 时走这里） */
    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodValidation(HandlerMethodValidationException e) {
        String message = e.getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return paramError(message);
    }

    /** 缺少必填请求参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return paramError("缺少必填参数: " + e.getParameterName());
    }

    /** multipart缺少文件或元数据时按参数错误终止，不暴露内部异常详情。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingPart(MissingServletRequestPartException e) {
        return paramError("缺少上传文件或元数据");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public Result<Void> handleUnsupportedMedia(HttpMediaTypeNotSupportedException e) {
        return Result.fail(415, "请求内容类型不支持，请按接口要求提交");
    }

    /** 参数类型不匹配，如 id=abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return paramError("参数类型错误: " + e.getName());
    }

    /** 请求体缺失或 JSON 格式错误 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        return paramError("请求体格式错误");
    }

    /** 路径不存在（Spring 6.1 起未匹配路径抛 NoResourceFoundException） */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(Exception e) {
        log.warn("资源不存在: {}", e.getMessage());
        return Result.fail(ResultCode.NOT_FOUND);
    }

    /** 请求方法不支持，如对 GET 接口发 POST */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMethod());
        return Result.fail(ResultCode.METHOD_NOT_ALLOWED);
    }

    /** 上传文件超过 spring.servlet.multipart.max-file-size / max-request-size */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件过大: {}", e.getMessage());
        return Result.fail(ResultCode.FILE_TOO_LARGE);
    }

    /** 异步线程池已满（线程达到 max-size 且队列已满），拒绝新任务；属于过载而非程序错误 */
    @ExceptionHandler(TaskRejectedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleTaskRejected(TaskRejectedException e) {
        log.warn("异步任务被拒绝，线程池已满: {}", e.getMessage());
        return Result.fail(ResultCode.SERVICE_BUSY);
    }

    /** 兜底：未预期的异常，只返回通用提示，详细信息仅写日志 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnknown(Exception e) {
        log.error("系统异常", e);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }

    private Result<Void> paramError(String message) {
        log.warn("参数错误: {}", message);
        return Result.fail(ResultCode.PARAM_ERROR, message);
    }
}
