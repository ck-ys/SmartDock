package org.dromara.assistant.automation.job;

import com.baomidou.lock.annotation.Lock4j;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.automation.service.AssistantAutomationService;
import org.dromara.assistant.automation.service.AutomationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 自动化扫描器（2026-11-05 拍板 题4 乙/题6 甲）。
 *
 * <p>每分钟一次：认领到期行（FOR UPDATE SKIP LOCKED，多实例各领各的；{@code @Lock4j}
 * 只做同时刻扫描去重）→ 事务提交后丢进执行线程池。另顺带收口超时悬挂的 RUNNING。</p>
 *
 * <p>认领时先把 next_run_at 推进到下一次再执行（open-webui 同款）：即使进程随后崩溃，
 * 也不会因为 next_run_at 没变而下一分钟重复触发；错过的那次由运行记录留痕。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutomationScanJob {

    private final AssistantAutomationService automationService;
    private final AutomationRunner runner;

    @Lock4j(keys = {"'assistant-automation-scan'"})
    @Scheduled(fixedDelay = 60000, initialDelay = 20000)
    public void scan() {
        try {
            int swept = automationService.sweepStale();
            if (swept > 0) {
                log.warn("[Automation] 收口超时运行 {} 条", swept);
            }
        } catch (Exception e) {
            log.error("[Automation] 超时收口失败", e);
        }
        try {
            List<AssistantAutomationService.Claimed> claimed = automationService.claimDue();
            for (AssistantAutomationService.Claimed c : claimed) {
                runner.submit(c.tenantId(), c.runId());
            }
            if (!claimed.isEmpty()) {
                log.info("[Automation] 本轮认领 {} 条", claimed.size());
            }
        } catch (Exception e) {
            log.error("[Automation] 扫描认领失败", e);
        }
    }
}
