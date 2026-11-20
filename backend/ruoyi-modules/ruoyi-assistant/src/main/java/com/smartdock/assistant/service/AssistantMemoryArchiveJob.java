package com.smartdock.assistant.service;

import com.baomidou.lock.annotation.Lock4j;
import com.smartdock.assistant.support.AssistantMemoryFacts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 长期记忆归档 job（ckys3 解锁板题10 案1，2026-11-12；批 6 题 5 案 1，2026-11-20 从 legacy/job 搬入 service 层）。
 *
 * <p>每日 03:20，把 90 天没被注入过的 ACTIVE 记忆置 ARCHIVED（不删数据、可恢复）。
 * 判据=last_used_at（最近一次真正命中注入的时间），不是创建时间——从未被注入过的条目
 * 不归档（拍板页口径）。与 03:40 保留期清理、03:55 MCP 审计清理、02:30 钱包对账错峰。</p>
 *
 * <p><b>锁键口径</b>（lock4j 2.2.7：{@code prefix + ":" + (hasText(name) ? name : 类名+方法名)
 * + "#" + 各 key 的 SpEL 求值结果}）：不给 name 时键里含类全名，本类换了包名，键就跟着变。
 * 显式给 name 后真实键固定为 {@code lock4j:assistant-memory-archive-daily#assistant-memory-archive-daily}
 * （默认前缀 lock4j），此后再改类名/包名也不会漂移。代价是一次性键变更——滚动发布时新旧实例
 * 理论上各跑一次，但归档按 cutoff 幂等（同条件再归档 0 行），最坏重复一次空转，不伤数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantMemoryArchiveJob {

    private final AssistantMemoryService memoryService;

    @Lock4j(name = "assistant-memory-archive-daily", keys = {"'assistant-memory-archive-daily'"})
    @Scheduled(cron = "0 20 3 * * ?")
    public void archiveStaleMemories() {
        try {
            int n = memoryService.archiveStale();
            if (n > 0) {
                log.info("[AssistantMemoryArchiveJob] 归档任务完成：{} 条（>{} 天未用）",
                    n, AssistantMemoryFacts.ARCHIVE_DAYS);
            }
        } catch (RuntimeException e) {
            log.warn("[AssistantMemoryArchiveJob] 归档任务失败（不连坐服务）：{}", e.getMessage());
        }
    }
}
