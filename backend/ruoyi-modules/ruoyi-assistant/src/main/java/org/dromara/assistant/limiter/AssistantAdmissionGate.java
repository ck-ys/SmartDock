package org.dromara.assistant.limiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.ConfigService;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.redisson.api.RScript;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 助手准入闸(批D D2): Redis ZSET 滑动窗口双限(租户级/全站级活跃用户数)。
 *
 * <p>机制: member="{tenantId}:{userId}", score=最近活跃时间戳; 每次请求原子执行
 * "清过期 → 在册成员续期(ZADD 刷新, 避免 TTL 蒸发造成重复计数) → 新成员判限入册"。
 * 判限与入册在同一 Lua 脚本内完成, 多实例并发下无竞态窗口; ZSET 共享于 Redis,
 * 天然跨实例生效。</p>
 *
 * <p>上限与窗口读 sys_config(000000 全局参数): dcs.assistant.max-sessions-per-tenant=20 /
 * dcs.assistant.max-sessions-global=200 / dcs.assistant.session-window-minutes=30;
 * 缺失走代码默认(与种子同值, 缓存空值陈旧也不改变行为)。</p>
 *
 * <p>超限话术统一「当前使用人数较多，请稍后再试」——按窗口自动释放, 不承诺
 * "明日恢复"之类系统做不到的时点(钱包欠费话术在计费装饰器, 语义分开)。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantAdmissionGate {

    /** 租户级活跃上限参数键 */
    public static final String CONFIG_TENANT_MAX = "dcs.assistant.max-sessions-per-tenant";
    /** 全站级活跃上限参数键 */
    public static final String CONFIG_GLOBAL_MAX = "dcs.assistant.max-sessions-global";
    /** 活跃窗口(分钟)参数键 */
    public static final String CONFIG_WINDOW_MINUTES = "dcs.assistant.session-window-minutes";

    static final int DEFAULT_TENANT_MAX = 20;
    static final int DEFAULT_GLOBAL_MAX = 200;
    static final int DEFAULT_WINDOW_MINUTES = 30;

    /** 超限提示(前端按文案映射重试引导, 批E) */
    static final String BUSY_MESSAGE = "当前使用人数较多，请稍后再试";

    /**
     * 原子准入: 返回 0=放行(在册或新入), 1=租户超限, 2=全站超限。
     * 两个 key 全程同窗口清理+同成员维护, 成员集合同步; key TTL=窗口+60s(空集兜底回收)。
     */
    static final String ADMIT_LUA = """
        local now = tonumber(ARGV[1])
        local cutoff = now - tonumber(ARGV[2])
        local ttlMs = tonumber(ARGV[2]) + 60000
        local tenantMax = tonumber(ARGV[3])
        local globalMax = tonumber(ARGV[4])
        local member = ARGV[5]
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', cutoff)
        redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', cutoff)
        if redis.call('ZSCORE', KEYS[1], member) then
          redis.call('ZADD', KEYS[1], now, member)
          redis.call('ZADD', KEYS[2], now, member)
          redis.call('PEXPIRE', KEYS[1], ttlMs)
          redis.call('PEXPIRE', KEYS[2], ttlMs)
          return 0
        end
        if redis.call('ZCARD', KEYS[1]) >= tenantMax then return 1 end
        if redis.call('ZCARD', KEYS[2]) >= globalMax then return 2 end
        redis.call('ZADD', KEYS[1], now, member)
        redis.call('ZADD', KEYS[2], now, member)
        redis.call('PEXPIRE', KEYS[1], ttlMs)
        redis.call('PEXPIRE', KEYS[2], ttlMs)
        return 0
        """;

    private final ConfigService configService;

    /**
     * 请求线程准入入口: 读 sys_config 上限, 超限抛 ServiceException。
     */
    public void admit(String tenantId, Long userId) {
        admit(tenantId, userId, readLimit(CONFIG_TENANT_MAX, tenantId, DEFAULT_TENANT_MAX),
            readLimit(CONFIG_GLOBAL_MAX, tenantId, DEFAULT_GLOBAL_MAX),
            readLimit(CONFIG_WINDOW_MINUTES, tenantId, DEFAULT_WINDOW_MINUTES) * 60_000L);
    }

    /**
     * 显式参数版(单测/压测入口, 不读配置)。
     *
     * @throws ServiceException 超限时(租户级/全站级同一话术)
     */
    public void admit(String tenantId, Long userId, int tenantMax, int globalMax, long windowMs) {
        // 0=运营急停(sys_config 可动态关停助手, 无需发版)
        if (tenantMax == 0 || globalMax == 0) {
            log.info("[AssistantAdmission] 急停拦截: 租户 {} 用户 {} (tenantMax={} globalMax={})",
                tenantId, userId, tenantMax, globalMax);
            throw new ServiceException(BUSY_MESSAGE);
        }
        String member = tenantId + ":" + userId;
        String tenantKey = "dcs:assistant:admit:tenant:" + tenantId;
        String globalKey = "dcs:assistant:admit:global";
        int v;
        try {
            RScript script = RedisUtils.getClient().getScript(StringCodec.INSTANCE);
            Long verdict = script.eval(RScript.Mode.READ_WRITE, ADMIT_LUA, RScript.ReturnType.INTEGER,
                List.of(tenantKey, globalKey),
                String.valueOf(System.currentTimeMillis()), String.valueOf(windowMs),
                String.valueOf(tenantMax), String.valueOf(globalMax), member);
            v = verdict == null ? 0 : verdict.intValue();
        } catch (Exception redisDown) {
            // fail-close(dspro 认可): 宁拒不放过; 包成话术化 503 而非裸 500 堆栈
            log.error("[AssistantAdmission] Redis 不可用, 准入闸 fail-close", redisDown);
            throw new ServiceException("AI 助手暂不可用，请稍后再试", 503);
        }
        if (v != 0) {
            log.info("[AssistantAdmission] 准入拦截: 租户 {} 用户 {} 命中{}上限",
                tenantId, userId, v == 1 ? "租户级" : "全站级");
            throw new ServiceException(BUSY_MESSAGE);
        }
    }

    /**
     * 基础设施健康探针（批G G1 status 端点 degraded 标志）：Redis 可达即 true。
     */
    public boolean healthy() {
        try {
            RedisUtils.getClient().getScript(StringCodec.INSTANCE)
                .eval(RScript.Mode.READ_ONLY, "return 1", RScript.ReturnType.INTEGER, List.of());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 读限额参数，两级回退（O-4 修，2026-10-06）：先 dynamic(本租户宇宙) 读——参数设置界面
     * PUT 走 @CachePut 刷的就是该宇宙，租户改自己的限额即时生效；读不到再 ignore 读
     * 000000 全局种子行（sys_config 按租户隔离，多租户部署的兜底）。
     * 0=合法值(运营急停开关, dsflash P2-9); 负值/缺失/异常=代码默认。
     */
    private int readLimit(String key, String tenantId, int defaultValue) {
        try {
            Integer value = tenantId == null ? null
                : TenantHelper.dynamic(tenantId, () -> configService.getConfigInt(key));
            if (value == null) {
                value = TenantHelper.ignore(() -> configService.getConfigInt(key));
            }
            if (value == null || value < 0) {
                return defaultValue;
            }
            return value;
        } catch (Exception e) {
            log.warn("[AssistantAdmission] 上限配置读取失败, 用默认值 {} key={}", defaultValue, key, e);
            return defaultValue;
        }
    }
}
