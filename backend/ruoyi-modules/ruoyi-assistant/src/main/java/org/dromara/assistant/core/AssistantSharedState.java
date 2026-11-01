package org.dromara.assistant.core;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 助手跨实例共享状态底座（多实例批 0）。
 *
 * <p>只放“重启/故障转移后不能丢”的跨请求业务状态：计划待确认/处理结果、once 批准、
 * 规则装回标记、实例租约。**不放回答正文、用户反问答案、SSE 事件流**——这些跟着
 * 本机流线程走，搬进 Redis 既增加延迟，也多一份敏感内容落盘面。</p>
 *
 * <p>三种部署模式（部署级环境变量，不进租户配置）：</p>
 * <ul>
 *   <li>{@code local}：完全走现有本机实现，Redis 零依赖（回滚/单机默认）；</li>
 *   <li>{@code dual}：写本机 + Redis，读本机并与 Redis 比对差异（灰度验证，不承诺跨实例正确性）；</li>
 *   <li>{@code redis}：读 Redis，Redis 不可用时由调用方 fail-closed（例如计划闸读不到就拒绝写操作）。</li>
 * </ul>
 */
@Slf4j
@Component
public class AssistantSharedState {

    /**
     * 统一 key 前缀；服务拆分后各服务必须共用同一份 contract，不许各写一套。
     *
     * <p><b>必须带 {@code global:}</b>：多租户 Redis NameMapper（TenantKeyPrefixHandler）
     * 会对不含 global: 的 key 按“当前线程租户”加前缀。助手 run 在无 Sa-Token 的裸线程里
     * 执行（拿不到租户），而 REST 线程能拿到——同一逻辑 key 会分裂成
     * {@code smartdock:asst:x} 与 {@code 000000:smartdock:asst:x} 两份，计划闸和实例
     * 心跳互相看不见（三审 A/B 实证）。跨租户安全由 pending 里的 owner 三元组与会话
     * 归属校验保证，不依赖 key 前缀。</p>
     */
    public static final String KEY_PREFIX = "global:smartdock:asst:";

    private static final String LUA_COMPARE_AND_DELETE =
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    /** 加集合成员 + 代次自增原子完成（once 批准跨实例重建判据，避免半成功） */
    private static final String LUA_SET_ADD_AND_INCR =
        "redis.call('sadd', KEYS[1], ARGV[1])"
            + " redis.call('expire', KEYS[1], ARGV[2])"
            + " local n = redis.call('incr', KEYS[2])"
            + " redis.call('expire', KEYS[2], ARGV[2])"
            + " return n";

    private final RedissonClient client;
    private final String mode;

    @Autowired
    public AssistantSharedState(RedissonClient client,
                                @Value("${dcs.assistant.shared-state.mode:local}") String mode) {
        this.client = client;
        this.mode = normalize(mode);
        log.info("[assistant-shared] 共享状态模式={}（local=本机 / dual=双写比对 / redis=跨实例）", this.mode);
    }

    /** 供纯单测/无 Spring 场景使用：全部走本机，不碰 Redis */
    public AssistantSharedState() {
        this.client = null;
        this.mode = "local";
    }

    private static String normalize(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase();
        return switch (v) {
            case "redis", "dual" -> v;
            default -> "local";
        };
    }

    public String mode() {
        return mode;
    }

    /** dual/redis：允许写 Redis；local：零 Redis 依赖 */
    public boolean redisEnabled() {
        return !"local".equals(mode) && client != null;
    }

    /** redis：读 Redis 为准；dual：读本机并比对 */
    public boolean redisReadPreferred() {
        return "redis".equals(mode) && client != null;
    }

    public static String key(String suffix) {
        return KEY_PREFIX + suffix;
    }

    public String get(String key) {
        RBucket<String> bucket = client.getBucket(key, StringCodec.INSTANCE);
        return bucket.get();
    }

    public void set(String key, String value, Duration ttl) {
        RBucket<String> bucket = client.getBucket(key, StringCodec.INSTANCE);
        bucket.set(value, ttl);
    }

    public void delete(String key) {
        client.getBucket(key, StringCodec.INSTANCE).delete();
    }

    public boolean exists(String key) {
        return client.getBucket(key, StringCodec.INSTANCE).isExists();
    }

    /**
     * 原子“比较并删除”：值等于 expected 才删，返回是否删成功。
     * 计划确认/取消靠它保证多标签页抢点只成功一次。
     */
    public boolean compareAndDelete(String key, String expected) {
        if (expected == null) {
            return false;
        }
        RScript script = client.getScript(StringCodec.INSTANCE);
        Object result = script.eval(RScript.Mode.READ_WRITE, LUA_COMPARE_AND_DELETE,
            RScript.ReturnType.INTEGER, List.of(key), expected);
        return result instanceof Number n && n.longValue() == 1L;
    }

    public Set<String> setMembers(String key) {
        RSet<String> set = client.getSet(key, StringCodec.INSTANCE);
        return new LinkedHashSet<>(set.readAll());
    }

    public void addToSet(String key, String value, Duration ttl) {
        RSet<String> set = client.getSet(key, StringCodec.INSTANCE);
        set.add(value);
        set.expire(ttl);
    }

    /** 集合加成员 + 代次自增（同一次 Lua，避免“成员写了、代次没写”的半成功） */
    public long addToSetAndIncrement(String setKey, String value, String counterKey, Duration ttl) {
        RScript script = client.getScript(StringCodec.INSTANCE);
        Object result = script.eval(RScript.Mode.READ_WRITE, LUA_SET_ADD_AND_INCR,
            RScript.ReturnType.INTEGER, List.of(setKey, counterKey), value,
            String.valueOf(Math.max(1L, ttl.getSeconds())));
        return result instanceof Number n ? n.longValue() : 0L;
    }

    public long increment(String key, Duration ttl) {
        RAtomicLong counter = client.getAtomicLong(key);
        long value = counter.incrementAndGet();
        counter.expire(ttl);
        return value;
    }

    public long getLong(String key, long fallback) {
        RAtomicLong counter = client.getAtomicLong(key);
        long value = counter.get();
        return value == 0L ? fallback : value;
    }

    public boolean getFlag(String key) {
        String v = get(key);
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    public void setFlag(String key, boolean value, Duration ttl) {
        set(key, value ? "1" : "0", ttl);
    }
}
