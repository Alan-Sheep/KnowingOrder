package com.ragqa.utils;

import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;

/**
 * 业务校验工具类（占位，按需扩展）。
 *
 * <p>用于 service 层的业务规则断言，失败时抛 {@link BusinessException}，由全局异常处理器统一转换。
 * 入参格式校验优先使用 Bean Validation 注解（@NotBlank 等），本类只处理注解表达不了的业务规则。</p>
 */
public final class ValidateUtils {

    private ValidateUtils() {
    }

    /** 条件不成立时抛业务异常 */
    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new BusinessException(message);
        }
    }

    /** 对象为 null 时抛「资源不存在」，常用于按 ID 查询后的判空 */
    public static <T> T notNull(T obj, String message) {
        if (obj == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, message);
        }
        return obj;
    }

    /** 字符串为空白时抛参数错误 */
    public static String notBlank(String str, String message) {
        if (StrUtils.isBlank(str)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, message);
        }
        return str;
    }
}
