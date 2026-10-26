package com.ragqa.common;

import lombok.Getter;

/**
 * 统一响应结果，所有接口均返回该结构。
 *
 * <pre>
 * { "code": 0, "message": "成功", "data": {...}, "timestamp": 1700000000000 }
 * </pre>
 *
 * @param <T> 业务数据类型
 */
@Getter
public final class Result<T> {

    /** 响应码，0 表示成功，见 {@link ResultCode} */
    private final int code;

    /** 提示信息 */
    private final String message;

    /** 业务数据，失败时为 null */
    private final T data;

    /** 响应时间戳（毫秒） */
    private final long timestamp;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        return fail(resultCode.getCode(), resultCode.getMessage());
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return fail(resultCode.getCode(), message);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
