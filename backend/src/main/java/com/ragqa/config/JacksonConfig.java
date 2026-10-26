package com.ragqa.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.ragqa.common.Constants;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * Jackson 全局序列化配置。
 *
 * <ul>
 *     <li>LocalDateTime / LocalDate 按 {@link Constants#DATE_TIME_PATTERN} / {@link Constants#DATE_PATTERN} 收发</li>
 *     <li>Long 包装类型输出为字符串：BIGINT 主键超过 JS 安全整数（2^53-1）会丢精度；
 *         基本类型 long（如 Result.timestamp）不受影响，仍输出数字</li>
 * </ul>
 *
 * <p>采用 Customizer 而非自建 ObjectMapper，保留 Spring Boot 的其余默认配置。</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(Constants.DATE_TIME_PATTERN);
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(Constants.DATE_PATTERN);
        return builder -> builder
                .timeZone(TimeZone.getTimeZone(Constants.TIME_ZONE))
                .serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter))
                .deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter))
                .serializerByType(LocalDate.class, new LocalDateSerializer(dateFormatter))
                .deserializerByType(LocalDate.class, new LocalDateDeserializer(dateFormatter))
                .serializerByType(Long.class, ToStringSerializer.instance);
    }
}
