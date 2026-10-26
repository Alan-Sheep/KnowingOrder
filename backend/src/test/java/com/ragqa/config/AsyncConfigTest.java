package com.ragqa.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证异步与定时线程池按 application.yml 配置生效。
 *
 * <p>只加载任务相关的两个自动配置与 {@link AsyncConfig}，不起数据源与 Web，无需 MySQL。</p>
 *
 * <p>重点防两类「看着正常其实失效」的问题：</p>
 * <ul>
 *     <li>yml 参数没绑上：线程池仍是默认值（core=8、队列无界），只有压测时才会暴露</li>
 *     <li>@Async 没走自定义线程池：开启 @EnableScheduling 后容器里同时有执行器和调度器，
 *         若自动配置的执行器被跳过，@Async 会退回到每个任务新建线程的简单执行器</li>
 * </ul>
 */
@SpringBootTest(classes = {AsyncConfig.class, AsyncConfigTest.AsyncProbe.class})
@ImportAutoConfiguration({TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class})
@Import(AsyncConfigTest.AsyncProbe.class)
class AsyncConfigTest {

    @Autowired
    private ThreadPoolTaskExecutor taskExecutor;

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    private AsyncProbe asyncProbe;

    @Test
    @DisplayName("异步线程池参数取自 spring.task.execution")
    void executorUsesYmlSettings() {
        assertThat(taskExecutor.getCorePoolSize()).isEqualTo(4);
        assertThat(taskExecutor.getMaxPoolSize()).isEqualTo(8);
        assertThat(taskExecutor.getQueueCapacity()).as("有界队列").isEqualTo(100);
        assertThat(taskExecutor.getThreadNamePrefix()).isEqualTo("rag-async-");
    }

    @Test
    @DisplayName("定时线程池参数取自 spring.task.scheduling")
    void schedulerUsesYmlSettings() {
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
        assertThat(taskScheduler.getThreadNamePrefix()).isEqualTo("rag-sched-");
    }

    @Test
    @DisplayName("@Async 方法实际跑在 rag-async- 线程上，而不是调用线程或临时线程")
    void asyncRunsOnConfiguredPool() throws Exception {
        String caller = Thread.currentThread().getName();
        String worker = asyncProbe.currentThreadName().get(5, TimeUnit.SECONDS);

        assertThat(worker).as("确实异步执行").isNotEqualTo(caller);
        assertThat(worker).as("走的是配置的线程池").startsWith("rag-async-");
    }

    /** 测试用异步 Bean：@Async 基于代理，必须由另一个 Bean 调用才会异步 */
    @Component
    static class AsyncProbe {

        @Async
        public CompletableFuture<String> currentThreadName() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }
}
