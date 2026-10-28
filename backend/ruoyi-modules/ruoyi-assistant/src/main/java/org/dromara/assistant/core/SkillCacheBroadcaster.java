package org.dromara.assistant.core;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 技能缓存失效广播（5b 批，题3=乙·Redis 广播全机立即失效）。
 *
 * <p>保存技能 → 本机即时失效＋向频道发布；各实例订阅收到即清 {@link AssistantSkillSource}
 * 与 {@link SkillAssets} 缓存——下一轮装载重新读库，全机秒级收敛。广播丢失由
 * 30 秒 TTL 兜底重读自愈（DatabaseSkillRepository/AssistantSkillSource 双层 TTL）。</p>
 *
 * <p>Redis 不可达只降级不阻断：订阅失败=本机仍有 30 秒重读兜底；发布失败=保存路径已先做
 * 本机失效，本机不受影响（多机最多晚 30 秒）。</p>
 */
@Slf4j
@Component
public class SkillCacheBroadcaster {

    /** 失效广播频道（消息体=触发失效的租户 id，仅作观测用，收到即全清） */
    public static final String CHANNEL = "smartdock:skill:reload";

    @PostConstruct
    void subscribe() {
        try {
            RedisUtils.getClient().getTopic(CHANNEL).addListener(String.class, (channel, tenantId) ->
                invalidateLocal("广播@" + tenantId));
            log.info("[skill-broadcast] 已订阅技能失效频道 {}", CHANNEL);
        } catch (Exception e) {
            log.warn("[skill-broadcast] 订阅失败（Redis 不可达？），退化为 30 秒兜底重读: {}", e.getMessage());
        }
    }

    /** 保存路径调用：本机立即失效＋发广播（本机订阅者收到会再清一次，幂等） */
    public static void publishReload(String tenantId) {
        invalidateLocal("保存@" + tenantId);
        try {
            RedisUtils.getClient().getTopic(CHANNEL).publish(tenantId == null ? "unknown" : tenantId);
        } catch (Exception e) {
            log.warn("[skill-broadcast] 广播发布失败（本机已即时失效，其余机器最迟 30 秒重读收敛）: {}",
                e.getMessage());
        }
    }

    private static void invalidateLocal(String why) {
        try {
            AssistantSkillSource.invalidateAll();
            log.info("[skill-broadcast] 技能缓存已失效（{}）", why);
        } catch (Exception e) {
            log.warn("[skill-broadcast] 失效执行异常: {}", e.getMessage());
        }
    }
}
