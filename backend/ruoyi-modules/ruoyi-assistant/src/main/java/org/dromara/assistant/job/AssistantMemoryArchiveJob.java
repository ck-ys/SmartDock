package org.dromara.assistant.job;

import com.baomidou.lock.annotation.Lock4j;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.service.AssistantMemoryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 长期记忆归档 job（ckys3 解锁板题10 案1，2026-11-12）：每日 03:20，把 90 天没被注入过的
 * ACTIVE 记忆置 ARCHIVED（不删数据、可恢复）。
 *
 * <p>判据=last_used_at（最近一次真正命中注入的时间），不是创建时间——从未被注入过的条目
 * 不归档（拍板页口径）。与 03:40 保留期清理、02:30 钱包对账错峰。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantMemoryArchiveJob {

    private final AssistantMemoryService memoryService;

    @Lock4j(keys = {"'assistant-memory-archive-daily'"})
    @Scheduled(cron = "0 20 3 * * ?")
    public void archiveStaleMemories() {
        try {
            int n = memoryService.archiveStale();
            if (n > 0) {
                log.info("[AssistantMemory] 归档任务完成：{} 条", n);
            }
        } catch (RuntimeException e) {
            log.warn("[AssistantMemory] 归档任务失败（不连坐服务）：{}", e.getMessage());
        }
    }
}
