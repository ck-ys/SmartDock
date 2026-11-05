package org.dromara.assistant.automation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 自动化执行线程池（2026-11-05 自动化批）。
 *
 * <p>与调度扫描线程隔离：扫描只做认领（毫秒级），模型调用可能跑几分钟。池小是有意的——
 * 每条自动化一天最多一次，并发峰值=同一分钟到点的几条；排队满时直接失败并提示手动重跑，
 * 不阻塞扫描器（拒绝策略 Abort）。</p>
 */
@Configuration
public class AutomationConfig {

    @Bean(name = "automationExecutor")
    public Executor automationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("automation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
