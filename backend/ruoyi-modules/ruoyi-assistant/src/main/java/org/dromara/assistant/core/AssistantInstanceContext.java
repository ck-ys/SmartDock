package org.dromara.assistant.core;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
    private final long heartbeatMs;
    /** 租约心跳独立守护线程（四审 dspro-P1-3：绝不能和业务 @Scheduled 抢 2 线程池） */
    private ScheduledExecutorService heartbeatScheduler;
    /** 本进程唯一令牌（与实例 id 分开：id 可由配置固定，令牌每次启动都不同） */
    private final String processToken = UUID.randomUUID().toString().replace("-", "");
    /** 本进程启动时刻（固定 instance.id 重启后，用来区分“自己名下的旧进程”）：见 stale 自愈 */
    private final long startedAtMillis = System.currentTimeMillis();

    public AssistantInstanceContext(
        AssistantSharedState shared,
        @Value("${dcs.assistant.instance.id:}") String configuredId,
        @Value("${dcs.assistant.instance.lease-seconds:45}") long leaseSeconds,
        @Value("${dcs.assistant.instance.heartbeat-ms:15000}") long heartbeatMs) {
        this.shared = shared;
        this.instanceId = (configuredId == null || configuredId.isBlank()) ? buildId() : configuredId.trim();
        this.heartbeatMs = Math.max(1_000L, heartbeatMs);
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
            // 四审回修（dsflash-P1-1）：local 节点把别台 owner 一律判死——全集群必须同模式，
            // 混跑（local + redis）会收掉对方正在跑的 run。模式是部署级变量，代码无法可靠
            // 探测另一台；已在 docs/deploy.md 的「多实例前置条件」里写死，滚动升级先摘流量。
            log.info("[assistant-instance] 实例 id={}（local 模式，不发 Redis 心跳）", instanceId);
            return;
        }
        // 四审回修（dsflash-P1-2）：实例 id 必须全集群唯一。固定 id 被复制到两台上时，
        // 双方都按 owner==self 认“本机活着”，会互相把对方的活 run 当自己名下的旧生命收掉。
        // 用 Redis 租约键做原子占位：旧进程还在续租就等它过期；一直不释放则拒绝启动。
        if (!claimInstanceId()) {
            throw new IllegalStateException("助手实例 id 已被另一个进程占用: " + instanceId
                + "（同一时刻全集群必须唯一；请改 dcs.assistant.instance.id 或先停旧进程）");
        }
        heartbeat();
        // 四审回修（dspro-P1-3）：心跳不再挂 @Scheduled。全应用 24 个定时任务共用
        // spring.task.scheduling.pool.size=2，夜间清理/全表扫描任一超过 45s 就会让心跳
        // 停摆，别机据此把本机正在跑的 run 收尸/停止——用户看到“回答被中断”，模型却还在
        // 出字计费。独立单线程守护进程与业务调度池彻底隔离。
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "assistant-instance-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleWithFixedDelay(this::heartbeat, heartbeatMs, heartbeatMs,
            TimeUnit.MILLISECONDS);
        log.info("[assistant-instance] 实例 id={} 已注册，租约 {} 秒（独立心跳线程）",
            instanceId, leaseSeconds);
    }

    /** 原子占位实例 id；旧进程遗留的租约最多等一个 TTL，超时=真冲突，拒绝启动 */
    private boolean claimInstanceId() {
        long deadline = System.currentTimeMillis() + (leaseSeconds + 5) * 1000L;
        while (true) {
            try {
                if (shared.setIfAbsent(leaseKey(instanceId), processToken,
                    Duration.ofSeconds(leaseSeconds))) {
                    return true;
                }
            } catch (Exception e) {
                // Redis 抖动不能把整个应用拦在启动口；心跳/SET 会继续告警，reconcile 由运维看
                log.warn("[assistant-instance] 实例 id 占位检查失败，先放行启动: {}", e.getMessage());
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    @PreDestroy
    void stop() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
        }
    }

    /** 独立线程每 15 秒续一次；45 秒没续视为机器挂了。Redis 抖动只告警，不阻断业务。 */
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
