package com.ragqa.exception;

import com.ragqa.common.ResultCode;
import lombok.Getter;

/**
 * 业务异常。service 层遇到可预期的业务失败时抛出，由全局异常处理器转换为统一响应。
 *
 * <p>属于"预期内"错误：仅由 GlobalExceptionHandler 记一次 WARN（含错误码与堆栈），
 * 其余位置（如 LogAspect）不要重复记录。</p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 对应的响应码 */
    private final int code;

    public BusinessException(String message) {
        this(ResultCode.BIZ_ERROR, message);
    }

    public BusinessException(ResultCode resultCode) {
        this(resultCode, resultCode.getMessage());
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
    }
}
