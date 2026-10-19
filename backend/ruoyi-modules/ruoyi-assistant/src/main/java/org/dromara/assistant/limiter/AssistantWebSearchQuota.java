package org.dromara.assistant.limiter;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.redisson.api.RScript;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 联网搜索日限额（业务批3-④ #17，2026-10-12）：每用户每日 web_search 次数，
 * 镜像 {@link AssistantDailyQuota} 回合闸口径——Redis 单条 Lua 原子「先比限后 INCR」
 * （被拒尝试不消耗额度，宁少拒不多扣）+ 首访 TTL 26h（跨日界自然蒸发）。
 *
 * <p>与回合闸的两点差异：</p>
 * <ul>
 *   <li>计数粒度=工具执行（一次模型回复可能调多次 web_search，每次都计数）；
 *       上游 HTTP 失败（非 200/超时/异常）由调用方 {@code refundSearch} 补偿——
 *       用户不为「没搜到」买单（与回合失败补偿同哲学）；</li>
 *   <li>限额源=effectiveLimits().websearchLimit（单人覆盖 &gt; 租户默认
 *       dcs_assistant_config.websearch_limit_per_user，批③三层合成的联网列）。</li>
 * </ul>
 *
 * <p>fail 口径：Redis 故障=放行本此搜索（回合闸已兜总频次+预算闸兜成本，
 * 联网计数属第三道细粒度限，宁可漏限一次不可打断已计费回合的回复流）。</p>
 */
@Slf4j
@Component
public class AssistantWebSearchQuota {

    /** 计数 key 模板（t:{tenantId}:quota:websearch:{userId}:{yyyyMMdd}，与回合键同命名族） */
    static final String SEARCH_KEY_TEMPLATE = "t:%s:quota:websearch:%s:%s";
    /** 首访 TTL：26h 覆盖全时区极值，跨日界自然蒸发 */
    static final long TTL_MS = 26 * 3600_000L;
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final ZoneId TZ = ZoneId.of("Asia/Shanghai");

    /** 搜索判定：先比限后 INCR（超限被拒不计数）+首访 TTL，单脚本原子（与回合闸同构） */
    static final String SEARCH_LUA = """
        local c = tonumber(redis.call('GET', KEYS[1]) or '0')
        if c >= tonumber(ARGV[1]) then return 0 end
        c = redis.call('INCR', KEYS[1])
        if c == 1 then redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2])) end
        return 1
        """;

    /** 今日已用联网搜索次数（status 端点展示用；Redis 故障=0 只读降级） */
    public long searchesUsed(String tenantId, Long userId) {
        try {
            RScript script = RedisUtils.getClient().getScript(StringCodec.INSTANCE);
            // PD-2 裸键契约（同 AssistantDailyQuota.turnsUsed）：读写一律 TenantHelper.ignore
            // 包住强制裸键，防「流线程写/请求线程读」前缀键形态错位读非所写
            Object v = TenantHelper.ignore(() -> script.eval(RScript.Mode.READ_ONLY,
                "return redis.call('GET', KEYS[1]) or '0'",
                RScript.ReturnType.VALUE, List.of(searchKey(tenantId, userId))));
            return v == null ? 0 : Long.parseLong(v.toString());
        } catch (Exception e) {
            log.warn("[WebSearchQuota] 已用次数读取失败, 展示按 0 降级 tenant={} user={}", tenantId, userId, e);
            return 0;
        }
    }

    /**
     * 搜索闸三态（dspro P2-1 回修）：补偿链只认 COUNTED。
     * fail-open 放行（PASSED_UNCOUNTED）若仍照退，DECR 会落在当日既有计数上——额度凭空多退。
     */
    public enum Gate {
        /** 已计数放行——上游失败时调用方可 refundSearch 补偿 */
        COUNTED,
        /** fail-open 放行未计数（Redis 故障窗）——不可退款 */
        PASSED_UNCOUNTED,
        /** 今日已用完或额度为 0——本次未计数 */
        DENIED
    }

    /**
     * 搜索闸（工具执行前置）：原子 INCR+比限，超限返回 {@link Gate#DENIED}（调用方转人话 JSON
     * 喂模型，不抛异常打断回复流——模型会告诉用户今日联网次数已用完）。
     *
     * @param limit 每用户每日联网搜索上限（effectiveLimits 联网列）
     * @return 三态：COUNTED=放行并已计数；PASSED_UNCOUNTED=Redis 故障放行未计数；DENIED=拒
     */
    public Gate trySearch(String tenantId, Long userId, int limit) {
        if (limit <= 0) {
            // 0=租户/单人侧把联网关到零（回合额度仍在）：当日全部搜索拒绝
            return Gate.DENIED;
        }
        try {
            RScript script = RedisUtils.getClient().getScript(StringCodec.INSTANCE);
            // PD-2 裸键契约（见 searchesUsed 注释）
            Long ok = TenantHelper.ignore(() -> script.eval(RScript.Mode.READ_WRITE, SEARCH_LUA,
                RScript.ReturnType.INTEGER,
                List.of(searchKey(tenantId, userId)), String.valueOf(limit), String.valueOf(TTL_MS)));
            return ok != null && ok == 1 ? Gate.COUNTED : Gate.DENIED;
        } catch (Exception redisDown) {
            // fail-open：计数服务故障不打断已计费回合的回复（回合闸+预算闸已兜总量）
            log.warn("[WebSearchQuota] Redis 不可用, 本次搜索放行(不计数) tenant={} user={}", tenantId, userId, redisDown);
            return Gate.PASSED_UNCOUNTED;
        }
    }

    /** 搜索补偿：上游 HTTP 失败/超时/异常时回退本次计数（用户不为「没搜到」买单） */
    public void refundSearch(String tenantId, Long userId) {
        try {
            RScript script = RedisUtils.getClient().getScript(StringCodec.INSTANCE);
            TenantHelper.ignore(() -> script.eval(RScript.Mode.READ_WRITE,
                "local c = tonumber(redis.call('GET', KEYS[1]) or '0')" +
                    " if c > 0 then redis.call('DECR', KEYS[1]) end return 1",
                RScript.ReturnType.INTEGER, List.of(searchKey(tenantId, userId))));
        } catch (Exception e) {
            log.warn("[WebSearchQuota] 搜索补偿失败(可容忍, 计数偏保守) tenant={} user={}", tenantId, userId);
        }
    }

    /**
     * 管理员重置某用户今日联网次数（批③用户额度页「重置今日」联动，业务批3-④ 接线）：
     * DEL 当日键——键格式与 trySearch/searchesUsed 写读逐字同源。Redis 故障抛
     * ServiceException（管理操作不静默假装成功，与回合 resetToday 同口径）。
     */
    public void resetToday(String tenantId, Long userId) {
        try {
            TenantHelper.ignore(() -> {
                RedisUtils.getClient().getBucket(searchKey(tenantId, userId)).delete();
                return null;
            });
            log.info("[WebSearchQuota] 管理员重置今日联网次数: tenant={} user={}", tenantId, userId);
        } catch (Exception e) {
            log.error("[WebSearchQuota] 重置失败(Redis): tenant={} user={}", tenantId, userId, e);
            throw new ServiceException("重置失败：计数服务暂不可用，请稍后再试", 503);
        }
    }

    private String searchKey(String tenantId, Long userId) {
        return String.format(SEARCH_KEY_TEMPLATE, tenantId, userId, LocalDate.now(TZ).format(DAY));
    }
}
