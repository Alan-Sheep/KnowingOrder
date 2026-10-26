package com.ragqa.utils;

import com.ragqa.common.Constants;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 日期工具类（占位，按需扩展）。基于 java.time，线程安全。
 */
public final class DateUtils {

    /** DateTimeFormatter 不可变且线程安全，可全局复用 */
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(Constants.DATE_TIME_PATTERN);

    private DateUtils() {
    }

    /** 格式化为 yyyy-MM-dd HH:mm:ss，入参为 null 时返回 null */
    public static String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMATTER);
    }

    /**
     * 解析 yyyy-MM-dd HH:mm:ss 字符串，空白时返回 null。
     *
     * @throws java.time.format.DateTimeParseException 格式不匹配时抛出
     */
    public static LocalDateTime parse(String text) {
        return StrUtils.isBlank(text) ? null : LocalDateTime.parse(text, DATE_TIME_FORMATTER);
    }
}
