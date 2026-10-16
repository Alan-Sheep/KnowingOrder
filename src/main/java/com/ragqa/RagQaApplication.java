package com.ragqa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RAG 智能问答系统启动类。
 *
 * <p>Mapper 扫描放在 {@code config.MybatisPlusConfig}，避免 Web 切片测试被迫加载数据源。</p>
 */
@SpringBootApplication
public class RagQaApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagQaApplication.class, args);
    }
}
