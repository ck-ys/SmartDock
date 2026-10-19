package org.dromara.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.AssistantUserQuota;
import org.dromara.assistant.limiter.AssistantDailyQuota;
import org.dromara.assistant.mapper.AssistantUserQuotaMapper;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户额度管理 Service（业务批3-③ #16，2026-10-12）。
 *
 * <p>三层合成口径：单人覆盖行（本表）&gt; 租户默认（dcs_assistant_config）——
 * {@link #effectiveLimits} 是「单人生效限额」的底层合成（额度页/管理页展示口径）；
 * 运行链拦截一律走 {@link #billingAwareLimits}（resolver 拦截链、status 端点、联网
 * 工具三消费方，订阅态感知）。禁用闸=覆盖行 disabled='1' 时该用户全部助手请求被拒（人话 429）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantUserQuotaService {

    private final AssistantUserQuotaMapper quotaMapper;
    private final AssistantConfigService configService;
    private final AssistantDailyQuota dailyQuota;
    private final org.dromara.assistant.limiter.AssistantWebSearchQuota webSearchQuota;
    /** 业务批7 Q1：订阅态判定+套餐联网档位（积分服务在 billing 模块，跨模块 DI 与 BillingModelDecorator 同源） */
    private final com.smartdock.service.IDcsAiCreditService creditService;

    /** 单人生效限额（展示+拦截共用；disabled=true 时 limits 仍返回供前端展示） */
    public record UserLimits(int roundLimit, int websearchLimit, boolean disabled) {
    }

    /**
     * 业务批7 Q1：计费感知限额视图——积分订阅租户「回合不限次 + 联网=套餐档位」，
     * 套餐积分本身就是消耗闸，再叠每日次数闸=用户感知重复限制。
     * 两道保护不撤：租户把回合/联网配 0（急停/关停）时仍按 0 拦截——紧急刹车不该被套餐绕过。
     * 套餐未配联网档位（0/NULL）时沿用租户默认联网上限。
     */
    public record BillingAwareLimits(int roundLimit, int websearchLimit, boolean disabled,
                                     boolean creditsMode, String planCode) {
    }

    /** 订阅期回合上限哨兵（tryTurn/remaining 均按此放行；前端 credits 模式隐藏该行） */
    public static final int UNLIMITED_ROUNDS = 1_000_000;

    public BillingAwareLimits billingAwareLimits(String tenantId, Long userId) {
        UserLimits ul = effectiveLimits(tenantId, userId);
        // dsflash P2-6：与 effectiveLimits 同款 fail-open——积分库闪断按钱包模式放行，
        // 不让三路消费方（resolver/status/websearch）跟着 500（宁可按默认放行不打断已过闸用户）
        java.util.Optional<com.smartdock.domain.DcsAiCreditSubscription> sub;
        try {
            // 业务批8 Q7 丁口径收口（2026-10-14 用户反馈暴露）：锚点与计费链
            // BillingModelDecorator 对齐——读租户显式消费方式开关 consumeMode（返回
            // CREDIT 即保证有生效订阅），消灭「手动切回钱包仍按积分限回合/联网档」分叉
            if (!"CREDIT".equals(creditService.consumeMode(tenantId))) {
                return new BillingAwareLimits(ul.roundLimit(), ul.websearchLimit(), ul.disabled(), false, null);
            }
            sub = creditService.activeSubscription(tenantId);
        } catch (Exception e) {
            log.warn("[AssistantQuota] 积分订阅查询闪断，按钱包模式放行 tenant={}", tenantId, e);
            return new BillingAwareLimits(ul.roundLimit(), ul.websearchLimit(), ul.disabled(), false, null);
        }
        if (sub.isEmpty()) {
            return new BillingAwareLimits(ul.roundLimit(), ul.websearchLimit(), ul.disabled(), false, null);
        }
        int round = ul.roundLimit() > 0 ? UNLIMITED_ROUNDS : ul.roundLimit();
        int web = ul.websearchLimit();
        if (web > 0) {
            try {
                web = creditService.subscriberWebsearchLimit(tenantId).orElse(web);
            } catch (Exception e) {
                log.warn("[AssistantQuota] 套餐联网档位查询闪断，沿用租户默认 tenant={}", tenantId, e);
            }
        }
        return new BillingAwareLimits(round, web, ul.disabled(), true, sub.get().getPlanCode());
    }

    /**
     * 单人生效限额合成：覆盖行非空列 &gt; 租户默认。
     * 查询失败（DB 不可达）时保守返回租户默认+未禁用（三席回修 m3 P2-1：与注释承诺对齐，
     * fail-open 而非 500——G1 租户开关闸已在前排兜 PG 整体故障，此处只兜「读到一半闪断」；
     * 宁可按默认放行也不打断已过闸用户的请求）。额度页降级展示同口径。
     */
    public UserLimits effectiveLimits(String tenantId, Long userId) {
        AssistantConfigService.EffectiveConfig cfg = configService.effective(tenantId);
        AssistantUserQuota q = null;
        try {
            q = quotaMapper.selectOne(
                new LambdaQueryWrapper<AssistantUserQuota>()
                    .eq(AssistantUserQuota::getTenantId, tenantId)
                    .eq(AssistantUserQuota::getUserId, userId));
        } catch (Exception e) {
            log.warn("[AssistantQuota] 覆盖行读取失败, 按租户默认+未禁用降级 tenant={} user={}", tenantId, userId, e);
        }
        int round = (q != null && q.getRoundLimitOverride() != null)
            ? q.getRoundLimitOverride() : cfg.dailyRoundLimitPerUser();
        // 联网列批④落地：单人覆盖 > 租户 dcs_assistant_config.websearch_limit_per_user（平台默认 20）
        int websearch = (q != null && q.getWebsearchLimitOverride() != null)
            ? q.getWebsearchLimitOverride() : cfg.websearchLimitPerUser();
        boolean disabled = q != null && "1".equals(q.getDisabled());
        return new UserLimits(round, websearch, disabled);
    }

    /**
     * 禁用闸（resolver 拦截链 G5 前调用）：disabled='1' 拒绝该用户全部助手请求。
     * 人话文案与其它闸同风格（不含技术细节，指明联系管理员）。
     */
    public void assertEnabled(String tenantId, Long userId) {
        UserLimits limits = effectiveLimits(tenantId, userId);
        if (limits.disabled()) {
            throw new ServiceException("管理员已暂停你的 AI 助手使用，如有疑问请联系管理员", 429);
        }
    }

    /**
     * 重置某用户今日额度：DEL 当日回合计数键 + 联网搜索计数键（业务批3-④起两键同点重置，
     * 键格式内聚在各 limiter，与 tryTurn/trySearch 写读同源）。
     */
    public void resetToday(String tenantId, Long userId) {
        dailyQuota.resetToday(tenantId, userId);
        webSearchQuota.resetToday(tenantId, userId);
    }

    /**
     * 限额覆盖保存（null=清空该列跟随租户默认，批④起两列独立语义）。
     * 四象限分流：双空→clearLimits（全清）；双值→upsert（COALESCE 语义完整）；
     * 一值一空→定向单列 SQL（upsert 写不回 NULL，如 round=30 且联网跟随默认须显式清联网列——
     * 三席 P0-2 登记项兑现：防「恢复回合默认」静默抹掉联网单人上限）。
     */
    public void updateLimits(String tenantId, Long userId, Integer roundLimit, Integer websearchLimit) {
        validateLimit(roundLimit, "回合上限");
        validateLimit(websearchLimit, "联网次数上限");
        boolean roundNull = roundLimit == null;
        boolean webNull = websearchLimit == null;
        if (roundNull && webNull) {
            quotaMapper.clearLimits(tenantId, userId);
            return;
        }
        AssistantUserQuota q = new AssistantUserQuota();
        q.setTenantId(tenantId);
        q.setUserId(userId);
        q.setRoundLimitOverride(roundLimit);
        q.setWebsearchLimitOverride(websearchLimit);
        if (!roundNull && webNull) {
            quotaMapper.upsertRoundClearWeb(q);
        } else if (roundNull) {
            quotaMapper.upsertWebClearRound(q);
        } else {
            quotaMapper.upsert(q);
        }
    }

    /** 禁用/启用（disabled='1'/'0'；upsert 兼首插） */
    public void setDisabled(String tenantId, Long userId, boolean disabled) {
        AssistantUserQuota q = new AssistantUserQuota();
        q.setTenantId(tenantId);
        q.setUserId(userId);
        q.setDisabled(disabled ? "1" : "0");
        quotaMapper.upsert(q);
    }

    /**
     * 用户额度页数据（分页行）：联表行 + 逐行补今日回合（Redis）与本月费用（一条聚合 SQL）。
     * 费用谓词与管理页统计头同口径（caller='ASSISTANT_CHAT'，月界 Asia/Shanghai）。
     * Mapper Map 键=PG 小写折叠（userid/nickname/...），此处重组为前端驼峰契约。
     */
    public Map<String, Object> pageList(String tenantId, String keyword, String disabledOnly,
                                        int pageNum, int pageSize) {
        int offset = Math.max(0, (pageNum - 1) * pageSize);
        List<Map<String, Object>> raw = quotaMapper.selectQuotaPage(
            tenantId, blankToNull(keyword), "1".equals(disabledOnly) ? "1" : null, pageSize, offset);
        long total = quotaMapper.countQuotaPage(
            tenantId, blankToNull(keyword), "1".equals(disabledOnly) ? "1" : null);
        // 本月费用一次聚合（页内 userId 列表）；今日回合逐行读 Redis（页面 ≤50 行可接受）
        List<Long> userIds = raw.stream().map(r -> ((Number) r.get("userid")).longValue()).toList();
        Map<Long, Object> costMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (Map<String, Object> c : quotaMapper.selectMonthCostByUsers(tenantId, userIds)) {
                costMap.put(((Number) c.get("userid")).longValue(), c.get("monthcost"));
            }
        }
        AssistantConfigService.EffectiveConfig cfg = configService.effective(tenantId);
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map<String, Object> r : raw) {
            Long uid = ((Number) r.get("userid")).longValue();
            long used = dailyQuota.turnsUsed(tenantId, uid);
            Object roundOverride = r.get("roundoverride");
            int effectiveRound = roundOverride != null ? ((Number) roundOverride).intValue() : cfg.dailyRoundLimitPerUser();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", uid);
            row.put("nickName", r.get("nickname"));
            row.put("userName", r.get("username"));
            row.put("deptName", r.get("deptname"));
            row.put("roundLimitOverride", roundOverride);
            row.put("websearchLimitOverride", r.get("websearchoverride"));
            row.put("disabled", r.get("disabled"));
            row.put("turnsUsedToday", used);
            row.put("effectiveRoundLimit", effectiveRound);
            row.put("monthCost", costMap.getOrDefault(uid, 0));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        return out;
    }

    private void validateLimit(Integer limit, String label) {
        if (limit == null) {
            return;
        }
        if (limit < 1 || limit > 10000) {
            throw new ServiceException(label + "须在 1~10000 之间", 400);
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
