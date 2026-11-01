package org.dromara.assistant.core;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;

/**
 * 当前助手实例身份 + 租约（多实例批 0）。
 *
 * <p>实例 id 默认 {@code hostname:pid:随机短码}；每 15 秒往 Redis 续一次 45 秒 TTL 的心跳。
 * 清理/启动自愈只碰“自己”或“owner 租约已死”的 run/会话；local 模式不发心跳、不依赖 Redis。</p>
 *
 * <p>租约只证明“这台机器还活着”，不证明“某个 run 正在正常出事件”——run 级活性仍由
 * {@code last_event_at} 和本机纯心跳收割口径判断，两者不要混用。</p>
 */
@Slf4j
@Component
public class AssistantInstanceContext {

    private final AssistantSharedState shared;
    private final String instanceId;
    private final long leaseSeconds;
    /** 本进程启动时刻（固定 instance.id 重启后，用来区分“自己名下的旧进程”）：见 stale 自愈 */
    private final long startedAtMillis = System.currentTimeMillis();

    public AssistantInstanceContext(
        AssistantSharedState shared,
        @Value("${dcs.assistant.instance.id:}") String configuredId,
        @Value("${dcs.assistant.instance.lease-seconds:45}") long leaseSeconds,
        @Value("${dcs.assistant.instance.heartbeat-ms:15000}") long heartbeatMs) {
        this.shared = shared;
        this.instanceId = (configuredId == null || configuredId.isBlank()) ? buildId() : configuredId.trim();
        // 二审回修（dspro-P2-5）：租约至少是心跳的 3 倍——配成 15s/15s 时一次 GC 抖动
        // 就会让别机判本机死，进而把活 run 收尸/停止误杀
        long floor = Math.max(15L, Math.max(1L, heartbeatMs / 1000L) * 3L);
        this.leaseSeconds = Math.max(floor, leaseSeconds);
    }

    private static String buildId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        long pid = ProcessHandle.current().pid();
        String tail = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return host + ":" + pid + ":" + tail;
    }

    public String current() {
        return instanceId;
    }

    /** 本进程启动时刻（毫秒）：固定 instance.id 重启后判“自己名下的旧 run”用 */
    public long startedAtMillis() {
        return startedAtMillis;
    }

    public boolean redisEnabled() {
        return shared.redisEnabled();
    }

    /**
     * owner 是否还活着。
     * <ul>
     *   <li>owner 为空：false——旧数据没有归属，启动自愈/收割一律不碰；</li>
     *   <li>owner 是自己：true；</li>
     *   <li>其他：Redis 里有未过期心跳才算活；local 模式没有别的实例，返回 false。</li>
     * </ul>
     */
    public boolean isAlive(String owner) {
        if (owner == null || owner.isBlank()) {
            return false;
        }
        if (owner.equals(instanceId)) {
            return true;
        }
        if (!shared.redisEnabled()) {
            return false;
        }
        try {
            return shared.exists(leaseKey(owner));
        } catch (Exception e) {
            // Redis 抖动时不能按“已死”处理：收割器会抢收另一台正在跑的 run（三审 A/B P1）。
            // 宁可漏收（owner 真死时下一轮/15 分钟兜底还会收），也不能误杀活 run。
            log.warn("[assistant-instance] 租约查询失败，按 owner 仍活处理 owner={}: {}", owner, e.getMessage());
            return true;
        }
    }

    private static String leaseKey(String owner) {
        return AssistantSharedState.key("instance:" + owner);
    }

    @PostConstruct
    void start() {
        if (!shared.redisEnabled()) {
            log.info("[assistant-instance] 实例 id={}（local 模式，不发 Redis 心跳）", instanceId);
            return;
        }
        heartbeat();
        log.info("[assistant-instance] 实例 id={} 已注册，租约 {} 秒", instanceId, leaseSeconds);
    }

    /** 15 秒续一次；45 秒没续视为机器挂了。Redis 抖动只告警，不阻断业务。 */
    @Scheduled(fixedDelayString = "${dcs.assistant.instance.heartbeat-ms:15000}",
        initialDelayString = "${dcs.assistant.instance.heartbeat-ms:15000}")
    public void heartbeat() {
        if (!shared.redisEnabled()) {
            return;
        }
        try {
            shared.set(leaseKey(instanceId), String.valueOf(System.currentTimeMillis()),
                Duration.ofSeconds(leaseSeconds));
        } catch (Exception e) {
            log.warn("[assistant-instance] 心跳失败（{} 秒后租约过期，别机可能接管本机活）: {}",
                leaseSeconds, e.getMessage());
        }
    }
}
