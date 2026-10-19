package org.dromara.assistant.persist;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantRun;
import org.dromara.assistant.limiter.AssistantDailyQuota;
import org.dromara.assistant.service.AssistantConversationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 助手保留期清理 job（批G G2，spec §4.4/P0-⑬）：每日 03:40。
 * message>90 天 / conversation·run·action>180 天，分批物理删；与 02:30 钱包对账 job
 * 错峰。agentscope 官方表副本（sessions 日志/卸载文件）由其会话行级联清理=P2 登记项。
 *
 * <p>悬挂 run 兜底（dspro P1-1，每分钟）：resolve 已扣回合但事件流未产出终态
 * （打错 agentId/框架侧同步异常走 SSE 错误帧=零事件）→ 15 分钟后迁移 INTERRUPTED
 * 并退回合——UPDATE...RETURNING 只对迁移成功的行退，天然防双退。</p>
 *
 * <p>悬挂 interrupt 缓存报读（二站 T3；三审回修 P0-2 改只读不删）：悬挂条目与框架
 * pending 同寿（框架无过期机制，javap 亲证），绝不时间清扫；本 job 只按超龄报读
 * 配对回收同 thread 的 stepBuffers（INTERRUPTED 滞留键无 run 终态到达，唯一兜底
 * 路径）。阈值 60 分钟=悬挂阈值 2 倍，防误伤 resume 轮在途 run 的步骤累积。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantRetentionJob {

    private final AssistantConversationService conversationService;
    private final AssistantDailyQuota dailyQuota;
    /** 二站 T3：悬挂缓存报读（只读，不删悬挂条目） */
    private final AssistantPendingInterruptCache pendingInterrupts;
    private final AssistantRunRecorder runRecorder;

    @Scheduled(cron = "0 40 3 * * ?")
    public void sweep() {
        try {
            conversationService.retentionSweep(5000);
        } catch (Exception e) {
            log.error("[AssistantRetention] 保留期清理失败(次日重试, 幂等安全)", e);
        }
    }

    /** 手动触发入口（管理/测试用） */
    public void sweepNow() {
        sweep();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void sweepStuckRuns() {
        try {
            List<DcsAssistantRun> closed = conversationService.closeStuckRuns(15);
            for (DcsAssistantRun r : closed) {
                dailyQuota.refundTurn(r.getTenantId(), r.getUserId());
            }
            if (!closed.isEmpty()) {
                log.warn("[AssistantRetention] 悬挂 run 兜底迁移 {} 行(15 分钟无终态事件, 已退回合)",
                    closed.size());
            }
        } catch (Exception e) {
            log.error("[AssistantRetention] 悬挂 run 兜底失败(下一轮重试, 幂等安全)", e);
        }
    }

    /**
     * 二站 T3：超龄悬挂 thread 的 stepBuffers 配对回收（三审回修 P0-2：悬挂缓存本身
     * 绝不清——框架 pending 永不过期，清了就单边失配毒化 thread）。60 分钟=悬挂阈值
     * 2 倍；静默清理不留审计（区别于入口自动解锁的动作留痕）。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 180_000)
    public void sweepStalePendingInterrupts() {
        try {
            List<String> stale = pendingInterrupts.staleThreadIds(60L * 60 * 1000);
            if (!stale.isEmpty()) {
                runRecorder.evictStepBuffers(stale);
                log.warn("[AssistantRetention] 超龄悬挂 thread stepBuffers 配对回收 {} 个"
                    + "(60 分钟, 悬挂条目按框架同寿保留)", stale.size());
            }
        } catch (Exception e) {
            log.error("[AssistantRetention] stepBuffers 配对回收失败(下一轮重试, 幂等安全)", e);
        }
    }

    /**
     * 二站② AU-42：agentscope 清除补偿重试——purge 四表已删但框架表清理失败时
     * 登记进 Redis 集合，这里每分钟重试直至干净（防 GDPR 半清除态复活旧历史）。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 240_000)
    public void retryPurgeCompensation() {
        try {
            java.util.Set<String> queue = conversationService.purgeCompensationQueue();
            for (String entry : queue) {
                int sep = entry.lastIndexOf(':');
                if (sep <= 0) {
                    conversationService.dequeuePurgeCompensation(entry);
                    continue;
                }
                String tenantId = entry.substring(0, sep);
                Long userId;
                try {
                    userId = Long.valueOf(entry.substring(sep + 1));
                } catch (NumberFormatException bad) {
                    conversationService.dequeuePurgeCompensation(entry);
                    continue;
                }
                if (conversationService.retryPurgeAgentscopeSessions(tenantId, userId)) {
                    conversationService.dequeuePurgeCompensation(entry);
                    log.info("[AssistantRetention] agentscope 清除补偿成功出队: {}", entry);
                }
            }
        } catch (Exception e) {
            log.error("[AssistantRetention] 清除补偿重试失败(下一轮重试, 幂等安全)", e);
        }
    }
}
