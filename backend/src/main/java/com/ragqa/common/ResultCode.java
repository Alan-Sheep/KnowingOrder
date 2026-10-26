package com.ragqa.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一响应码。
 *
 * <p>约定：0 表示成功；4xx/5xx 与 HTTP 语义对齐；1000 起为业务错误码，按模块分段扩展。</p>
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(0, "成功"),

    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "请先登录或登录已失效"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    CONFLICT(409, "记录已变化，请刷新后再操作"),
    FILE_TOO_LARGE(413, "上传文件过大"),

    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),
    SERVICE_BUSY(503, "服务繁忙，请稍后重试"),
    PROCESS_TIMEOUT(504, "处理超时，请缩小范围后重试"),

    BIZ_ERROR(1000, "业务处理失败");

    /** 响应码 */
    private final int code;

    /** 默认提示信息 */
    private final String message;
}
