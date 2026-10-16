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

    /**
     * 驼峰转下划线，如 {@code createdAt -> created_at}、{@code ID -> id}。
     *
     * <p>用于把接口传的驼峰字段名映射成数据库列名。转过之后的字符串仍然只由
     * 字母、数字、下划线组成，可安全用于 ORDER BY。</p>
     *
     * @param str 原字符串，为 null 或空白时原样返回
     */
    public static String camelToSnake(String str) {
        if (isBlank(str)) {
            return str;
        }
        StringBuilder result = new StringBuilder(str.length() + 4);
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c)) {
                // 连续大写（如 ID）视为一个词，只在「小写/数字 后面跟大写」时插下划线
                if (i > 0 && !Character.isUpperCase(str.charAt(i - 1))) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
