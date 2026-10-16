package com.ragqa.config;

import com.ragqa.utils.StrUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Web MVC 配置，目前只包含跨域（CORS）设置。
 *
 * <p>允许来源通过 {@code cors.allowed-origins} 配置，逗号分隔。<b>留空则不注册任何跨域规则</b>，
 * 这样生产环境不会因为缺少配置而误放行外部来源，同源请求也不受影响。</p>
 *
 * <p>注意：{@code @Value} 绑定数组只认逗号分隔的字符串，YAML 的列表写法（每行一个 - item）
 * 无法绑到 {@code String[]}，因此配置文件里要写成一行逗号分隔。</p>
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 只对业务接口开放跨域，放行静态资源等无必要也不安全 */
    private static final String CORS_PATH_PATTERN = "/api/**";

    /**
     * 允许的来源，逗号分隔。留空表示未配置，此时不注册任何跨域规则。
     *
     * <p>刻意绑成 {@code String} 再手工切分，而不是直接绑 {@code String[]}：
     * 数组绑定的空值行为依赖转换器实现（空串可能被解析成含空元素的数组），
     * 手工切分能顺带滤掉空白项和多余逗号，语义明确。</p>
     */
    @Value("${cors.allowed-origins:}")
    private String allowedOrigins;

    @Value("${cors.allowed-methods:GET,POST,PUT,DELETE,PATCH,OPTIONS}")
    private String[] allowedMethods;

    /** 预检请求结果的缓存秒数，减少 OPTIONS 往返 */
    @Value("${cors.max-age:3600}")
    private long maxAge;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = splitNonBlank(allowedOrigins);
        if (origins.length == 0) {
            log.info("未配置 cors.allowed-origins，跳过跨域配置（同源请求不受影响）");
            return;
        }
        registry.addMapping(CORS_PATH_PATTERN)
                .allowedOrigins(origins)
                .allowedMethods(allowedMethods)
                // allowedHeaders 用 * 是安全的：allowCredentials 为 true 时，
                // Spring 不会真的返回 "*"，而是把浏览器请求的头部原样回显，符合 CORS 规范
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(maxAge);
        log.info("已启用跨域配置: path={}, origins={}", CORS_PATH_PATTERN, Arrays.toString(origins));
    }

    /** 按逗号切分并丢弃空白项 */
    private String[] splitNonBlank(String value) {
        if (StrUtils.isBlank(value)) {
            return new String[0];
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StrUtils::isNotBlank)
                .toArray(String[]::new);
    }
}
