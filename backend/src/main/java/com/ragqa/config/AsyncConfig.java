package com.ragqa.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 异步任务与定时任务配置。
 *
 * <p>线程池本身由 Spring Boot 自动配置创建，参数在 application.yml 的
 * {@code spring.task.execution}（@Async）与 {@code spring.task.scheduling}（@Scheduled）中调整，
 * 这里不手动 new 线程池，避免与自动配置的执行器并存、@Async 选错执行器。</p>
 *
 * <p>本类只做两件事：开启 @Async / @Scheduled，以及兜住 {@code void} 异步方法里的未捕获异常。
 * 返回 {@code CompletableFuture} 的异步方法，异常会封装在 Future 中，由调用方处理。</p>
 *
 * <p>注意：@Async 基于代理，同一个类内部的方法互相调用不会走异步；
 * 异步方法所在的 Bean 需由其他 Bean 注入后调用。</p>
 */
@Slf4j
@EnableAsync
@EnableScheduling
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /**
     * void 异步方法抛出的异常没有调用方可以接住，这里统一记录方法名与异常堆栈。
     * 不记录任务参数，避免文档正文、凭据等内容进入日志。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("异步任务异常: method={}.{}",
                method.getDeclaringClass().getSimpleName(), method.getName(), ex);
    }
}
