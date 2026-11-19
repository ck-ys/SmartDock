package com.smartdock.assistant.service;

import com.baomidou.lock.annotation.Lock4j;
import com.smartdock.assistant.mapper.DcsMcpCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * MCP 审计保留期清理 job（G4-#5；批 5 题 5 案 1，2026-11-19 从 legacy 的
 * McpPilotService 拆成独立类——大类的唯一「无人调用」成员，拆出去让运行面只剩挂载与限频）。
 *
 * <p>保留 90 天，分批 5000 行防长事务锁表。cron 03:55：G4 三审回修（dspro P2）错峰——
 * 03:40 已被 AssistantRetentionJob/ProfileRetentionJob 占用；多实例批 3 起加 @Lock4j 单跑，
 * 防多节点同时删同一批。</p>
 *
 * <p>锁键口径（lock4j 2.2.7 字节码实证：{@code prefix + ":" + (hasText(name) ? name :
 * 类名+方法名) + "#" + 各 key 的 SpEL 求值结果}；{@code keys} 走 SpEL 解析，单引号只是
 * 字符串字面量记号，不进键值）：不给 name 时键里含类全名，本类换了包名与类名，键就跟着变。
 * 显式给 name 后真实键固定为 {@code lock4j:assistant-mcp-call-log-sweep#assistant-mcp-call-log-sweep}
 * （默认前缀 lock4j），此后再改类名/包名也不会漂移。代价是一次性的键变更——滚动发布时新旧
 * 实例理论上各跑一次，但删除按 cutoff 幂等（同条件再删 0 行），最坏是重复一次空转，不伤数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpCallLogRetentionJob {

    private final DcsMcpCallLogMapper callLogMapper;

    @Lock4j(name = "assistant-mcp-call-log-sweep", keys = {"'assistant-mcp-call-log-sweep'"})
    @Scheduled(cron = "0 55 3 * * ?")
    public void sweepExpiredCallLogs() {
        try {
            Date cutoff = new Date(System.currentTimeMillis() - 90L * 24 * 3600 * 1000);
            int total = 0;
            int n;
            do {
                n = callLogMapper.deleteBatchBefore(cutoff);
                total += n;
            } while (n >= 5000);
            if (total > 0) {
                log.info("[McpCallLogRetentionJob] 审计保留期清理：删除 {} 行（>90 天）", total);
            }
        } catch (Exception e) {
            // glm53 终审 P2：定时任务必须兜底（对照 AssistantRetentionJob 风格）——
            // 清理失败不连坐调度线程，明早重试
            log.error("[McpCallLogRetentionJob] 审计保留期清理失败（明日重试）：{}", e.getMessage(), e);
        }
    }
}
