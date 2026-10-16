package com.ragqa.utils;

/**
 * 字符串工具类（占位，按需扩展）。
 *
 * <p>命名为 StrUtils 以避免与 Spring 的 {@code org.springframework.util.StringUtils} 混淆导入。</p>
 */
public final class StrUtils {

    private StrUtils() {
    }

    /** null、空串或仅含空白字符时返回 true */
    public static boolean isBlank(String str) {
        return str == null || str.isBlank();
    }

    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }

    /**
     * 按字符数截断，超出部分以 "..." 结尾。常用于日志输出或会话标题生成。
     *
     * @param str    原字符串，可为 null
     * @param maxLen 最大保留字符数，必须大于 0
     */
    public static String truncate(String str, int maxLen) {
        if (maxLen <= 0) {
            throw new IllegalArgumentException("maxLen 必须大于 0");
        }
        if (str == null || str.length() <= maxLen) {
            return str;
        }
        return str.substring(0, maxLen) + "...";
    }
}
