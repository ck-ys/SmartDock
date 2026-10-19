package org.dromara.assistant.limiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.redisson.api.RScript;
import org.redisson.client.codec.StringCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 助手日限额（批G G5，Q5/Q16 兑现）：每用户每日回合数 + 租户日预算，Redis 单条 Lua 原子。
 *
 * <p>口径（spec §11）：计数缓存 key {@code t:{tenantId}:quota:*}，日界内嵌 yyyyMMdd
 * （Asia/Shanghai，S-41 三处统一之一）；禁 check-then-act（S-17）——回合判定=INCR 后
 * 比限一条脚本内完成；预算消费由计费装饰器异步记账（addSpend），闸口只读比对，
 * 计费落账的秒级迟滞=可接受误差（宁少拒不多扣，登记）。</p>
 *
 * <p>fail-close：Redis 故障=按已满处理+503（与准入闸同语义，§11）；回合失败补偿
 * refundTurn 供 resolver 后续环节异常时回退（模型流中途失败由 RUN_ERROR 路径补偿）。</p>
 *
 * <p>超限行为：知识包缓写（拍板④ 2026-09-30）——超限不降级检索，直接人话拒绝并
 * 告知恢复时点；二期知识包就绪后升级为「只答知识」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantDailyQuota {

    /** 计数 key 模板（t:{tenantId}:quota:turn:{userId}:{yyyyMMdd}，§11 命名口径） */
    static final String TURN_KEY_TEMPLATE = "t:%s:quota:turn:%s:%s";
    /** 计数 key TTL：跨过日界后自然蒸发（26h 覆盖全时区极值） */
    static final long TTL_MS = 26 * 3600_000L;
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final ZoneId TZ = ZoneId.of("Asia/Shanghai");

    /** 回合判定：先比限后 INCR（被拒尝试不消耗额度，宁少拒不多扣）+首访 TTL，单脚本原子 */
    static final String TURN_LUA = """
        local c = tonumber(redis.call('GET', KEYS[1]) or '0')
        if c >= tonumber(ARGV[1]) then return 0 end
        c = redis.call('INCR', KEYS[1])
        if c == 1 then redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2])) end
        return 1
        """;

    /** 预算真值源查询（ledger 直查，见 checkBudget javadoc） */
    private final JdbcTemplate jdbcTemplate;

    /** 今日已用回合数（status 端点展示用；Redis 故障=0 只读降级） */
    public long turnsUsed(String tenantId, Long userId) {
        try {
            RScript script = RedisUtils.getClient().getScript(StringCodec.INSTANCE);
            // PD-2(2026-10-07 试点走查, 根因二修): 键前缀=TenantKeyPrefixHandler 按当前线程
            // 租户上下文动态拼——tryTurn 跑流线程(无登录态)=裸键写入, 而 status 请求线程
            // (Sa-Token 上下文活跃)读 {tenantId}:裸键 前缀键, 读非所写余量恒 0(MONITOR 实锤
            // EVALSHA_RO 与写入 GET 键形态不同)。契约=三方法一律 TenantHelper.ignore 包住
            // 强制裸键(与流线程写入形态对称); 单测线程无上下文本就裸键, 行为不变。
            Object v = TenantHelper.ignore(() -> script.eval(RScript.Mode.READ_ONLY,
                "return redis.call('GET', KEYS[1]) or '0'",
                RScript.ReturnType.VALUE, List.of(trendKey(tenantId, userId))));
            return v == null ? 0 : Long.parseLong(v.toString());
        } catch (Exception e) {
            log.warn("[AssistantQuota] 已用回合数读取失败, 展示按 0 降级 tenant={} user={}",
                tenantId, userId, e);
            return 0;
        }
    }

    /**
     * 回合闸：原子 INCR+比限，超限抛话术异常（含恢复时点）。
     *
     * @param limit 每用户每日回合上限（dcs_assistant_config 生效值）
     */
    public void tryTurn(String tenantId, Long userId, int limit) {
        if (limit <= 0) {
            // 0=租户侧急停（区别于平台急停 sys_config）：当日全部拒绝
            throw new ServiceException("管理员已临时停用 AI 助手对话，请联系管理员", 503);
        }
        String key = trendKey(tenantId, userId);
        Long ok;
        try {
            RScript script = RedisUtils.getClient().getScript(StringCodec.INSTANCE);
            // PD-2: ignore 包住=裸键契约(见 turnsUsed 注释); 流线程无上下文时行为不变
            ok = TenantHelper.ignore(() -> script.eval(RScript.Mode.READ_WRITE, TURN_LUA,
                RScript.ReturnType.INTEGER,
                List.of(key), String.valueOf(limit), String.valueOf(TTL_MS)));
        } catch (Exception redisDown) {
            log.error("[AssistantQuota] Redis 不可用, 日限额 fail-close tenant={} user={}", tenantId, userId, redisDown);
            throw new ServiceException("AI 助手暂不可用，请稍后再试", 503);
        }
        if (ok == null || ok == 0) {
            throw new ServiceException("今日对话额度已用完（每人每日 " + limit
                + " 回合），明日 0 点自动恢复", 429);
        }
    }

    /** 回合补偿（resolver 后续环节异常 / 模型流 RUN_ERROR 时回退本回合计数） */
    public void refundTurn(String tenantId, Long userId) {
        try {
            RScript script = RedisUtils.getClient().getScript(StringCodec.INSTANCE);
            // PD-2: ignore 包住=裸键契约(见 turnsUsed 注释)
            TenantHelper.ignore(() -> script.eval(RScript.Mode.READ_WRITE,
                "local c = tonumber(redis.call('GET', KEYS[1]) or '0')" +
                    " if c > 0 then redis.call('DECR', KEYS[1]) end return 1",
                RScript.ReturnType.INTEGER, List.of(trendKey(tenantId, userId))));
        } catch (Exception e) {
            log.warn("[AssistantQuota] 回合补偿失败(可容忍, 计数偏保守) tenant={} user={}", tenantId, userId);
        }
    }

    /**
     * 管理员重置某用户今日回合计数（业务批3-③ #16）：DEL 当日键——键格式与写入
     * 逐字同源（trendKey）。Redis 故障抛 ServiceException（管理操作不静默假装成功）。
     */
    public void resetToday(String tenantId, Long userId) {
        try {
            TenantHelper.ignore(() -> {
                RedisUtils.getClient().getBucket(trendKey(tenantId, userId)).delete();
                return null;
            });
            log.info("[AssistantQuota] 管理员重置今日额度: tenant={} user={}", tenantId, userId);
        } catch (Exception e) {
            log.error("[AssistantQuota] 重置失败(Redis): tenant={} user={}", tenantId, userId, e);
            throw new ServiceException("重置失败：计数服务暂不可用，请稍后再试", 503);
        }
    }

    /**
     * 租户日预算闸：当日已消费 ≥ 预算 → 拒绝。
     * <p>消费真值源=dcs_tenant_wallet_ledger 当日 SUM（AI_ASSISTANT_CHAT 服务码，
     * 日界 Asia/Shanghai）——不走 Redis 累计（计费落账在流结束 doFinally，Redis
     * 副本必然迟滞且浮点累计有漂移；账本直查零漂移，spec §11 Redis 口径的偏差登记）。
     * 读失败≠停服（回合闸已兜频次），放行+告警。</p>
     *
     * <p>二站② AU-44：SUM 结果 30s Redis 短缓存（裸键 PD-2 契约）——预算判定本就
     * 「宁少拒不多扣」（计费落账秒级迟滞=已接受误差），30s 缓存同量级不改变语义，
     * 省掉每回合的全表 SUM；预算打满后每 30s 复核一次，充值即自然恢复。</p>
     */
    public void checkBudget(String tenantId, BigDecimal dailyBudget) {
        if (dailyBudget == null || dailyBudget.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        try {
            java.time.LocalDate today = LocalDate.now(TZ);
            String cacheKey = String.format("t:%s:quota:budget:%s", tenantId, today.format(DAY));
            BigDecimal used = TenantHelper.ignore(() ->
                org.dromara.common.redis.utils.RedisUtils.getCacheObject(cacheKey));
            if (used == null) {
                used = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM dcs_tenant_wallet_ledger"
                        + " WHERE tenant_id=? AND del_flag='0' AND service_code='AI_ASSISTANT_CHAT'"
                        + " AND direction='OUT' AND biz_type='DEDUCT'"
                        + " AND create_time >= ? AND create_time < ?",
                    BigDecimal.class, tenantId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
                if (used == null) {
                    used = BigDecimal.ZERO;
                }
                final BigDecimal snapshot = used;
                TenantHelper.ignore(() -> {
                    org.dromara.common.redis.utils.RedisUtils.setCacheObject(
                        cacheKey, snapshot, java.time.Duration.ofSeconds(30));
                    return null;
                });
            }
            if (used.compareTo(dailyBudget) >= 0) {
                throw new ServiceException("本租户今日 AI 助手预算已用完，明日 0 点自动恢复", 429);
            }
        } catch (ServiceException se) {
            throw se;
        } catch (Exception e) {
            log.warn("[AssistantQuota] 预算读失败, 本次放行 tenant={}", tenantId, e);
        }
    }

    private String trendKey(String tenantId, Long userId) {
        return String.format(TURN_KEY_TEMPLATE, tenantId, userId, LocalDate.now(TZ).format(DAY));
    }
}
