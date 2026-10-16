package com.ragqa.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JSON 工具类，复用 Spring 容器中已配置的 {@link ObjectMapper}。
 *
 * <p>复用容器的 ObjectMapper 而不是自己 new 一个，是为了与接口的序列化规则保持一致：
 * 日期格式、Long 转字符串、以及 {@code @JsonIgnore} 等注解都来自 JacksonConfig。
 * 例如 {@code SysUser.passwordHash} 标了 {@code @JsonIgnore}，因此这里序列化用户对象
 * 时该字段不会出现，日志中也就不会泄漏密码散列值。</p>
 *
 * <p>串化失败不抛异常：日志与调试用途的工具不应该反过来把业务流程搞挂。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonUtils {

    /** 序列化失败时的占位文本 */
    private static final String SERIALIZE_FAILED = "<序列化失败>";

    private final ObjectMapper objectMapper;

    /** 序列化为 JSON 字符串；入参为 null 返回 null，失败返回占位文本 */
    public String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: type={}, cause={}", obj.getClass().getName(), e.getMessage());
            return SERIALIZE_FAILED;
        }
    }
}
