package org.dromara.common.web.config;

import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * MDC 跨线程传播配置: 通过 Spring TaskDecorator 让 @Async / Spring 线程池
 * 自动复制 MDC(traceId) 到子线程, 解决 HTTP 请求→异步线程 traceId 丢失问题。
 *
 * <p>覆盖范围: @Async 方法、@TransactionalEventListener(AFTER_COMMIT + @Async)、
 * Spring 管理的 ThreadPoolTaskExecutor。Redis Stream consumer 是自建线程池(非 Spring
 * 管理), 需在 DcsStreamMqProducer/Consumer 手动把 traceId 塞进消息体再恢复 MDC。</p>
 *
 * @author smartdock
 */
@AutoConfiguration
public class ContextPropagationConfig {

    /**
     * 全局 TaskDecorator: 捕获提交线程的 MDC 快照, 在子线程恢复。
     * Spring Boot 3.5 的 @EnableAsync 默认 executor 会应用此 decorator。
     */
    @org.springframework.context.annotation.Bean
    public TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context != null) {
                    MDC.setContextMap(context);
                } else {
                    MDC.clear();
                }
                try {
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }
}
