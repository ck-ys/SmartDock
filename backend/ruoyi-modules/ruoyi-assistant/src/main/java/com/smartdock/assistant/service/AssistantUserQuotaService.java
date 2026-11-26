package com.smartdock.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import com.smartdock.assistant.infrastructure.AssistantWebSearchQuota;
import com.smartdock.assistant.service.AssistantConfigService;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.assistant.domain.model.AssistantUserQuota;
import com.smartdock.assistant.infrastructure.AssistantDailyQuota;
import com.smartdock.assistant.mapper.AssistantUserQuotaMapper;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户额度管理 Service（业务批3-③ #16，2026-10-12）。
 *
 * <p>三层合成口径：单人覆盖行（本表）&gt; 租户默认（assistant_config）——
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
    private final AssistantWebSearchQuota webSearchQuota;
    /** 业务批7 Q1：订阅态判定+套餐联网档位（积分服务在 billing 模块，跨模块 DI 与 BillingModelDecorator 同源） */
    private final com.smartdock.service.IAiCreditService creditService;

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

    /* ===== 额度三级管控批（2026-11-26 / Q4=3 + Q20=1）：每人每天积分上限 ===== */

    /** 「不限」记号（越过套餐建议值；只给积分/联网两列，回合列不给） */
    public static final int UNLIMITED_CREDIT = -1;
    /** 平台兜底数：套餐也没给建议值时用（Q4 案 3 的最后一环；企业可改企业默认列盖掉它） */
    public static final int PLATFORM_DAILY_CREDIT = 100;

    /**
     * 单人生效积分上限 + 它从哪来的（企业页要写「这个上限是哪来的」，Q8 补条件）。
     * value=-1 表示不限（调用方据此跳过闸）。
     */
    public record CreditLimit(int value, String source) {
        /** 来源码：personal=个人单独设的 / tenant=企业默认 / plan=套餐建议值 / platform=平台兜底 */
        public boolean unlimited() {
            return value == UNLIMITED_CREDIT;
        }
    }

    /**
     * 四级取值链（纯函数，单测直调）：个人覆盖 → 企业默认 → 套餐建议值 → 平台兜底。
     *
     * <p>每级里 null / -2 都是「没设，落到下一级」；<b>-1 = 不限</b>是明值，就地终止链条
     * （Q20=1：个人「不限」可越过套餐档，套餐是平台定的、个人是客户自己决定的）。</p>
     *
     * <p>套餐建议值的 0 与 null 同义（套餐页 0 = 没给建议，Q18=1），因为 0 在积分列上
     * 没有「急停」语义（那是回合/联网列的含义）。</p>
     */
    public static CreditLimit resolveCreditLimit(Integer personal, Integer tenantDefault, Integer planSuggested) {
        if (personal != null && personal != -2) {
            return new CreditLimit(personal, "personal");
        }
        if (tenantDefault != null && tenantDefault != -2) {
            return new CreditLimit(tenantDefault, "tenant");
        }
        if (planSuggested != null && planSuggested > 0) {
            return new CreditLimit(planSuggested, "plan");
        }
        return new CreditLimit(PLATFORM_DAILY_CREDIT, "platform");
    }

    /**
     * 读库版：把三处存储位读出来交给 {@link #resolveCreditLimit}。
     * 覆盖行读失败按「没设个人覆盖」降级（与 {@link #effectiveLimits} 同款 fail-open：
     * 宁可按企业默认放行，不打断已过闸用户）；套餐建议值查不到同样落到平台兜底。
     */
    public CreditLimit creditLimit(String tenantId, Long userId) {
        AssistantConfigService.EffectiveConfig cfg = configService.effective(tenantId);
        AssistantUserQuota q = null;
        try {
            q = quotaMapper.selectOne(
                new LambdaQueryWrapper<AssistantUserQuota>()
                    .eq(AssistantUserQuota::getTenantId, tenantId)
                    .eq(AssistantUserQuota::getUserId, userId));
        } catch (Exception e) {
            log.warn("[AssistantQuota] 积分覆盖行读取失败, 按企业默认降级 tenant={} user={}", tenantId, userId, e);
        }
        Integer planSuggested = null;
        try {
            planSuggested = creditService.subscriberSuggestedDailyCredit(tenantId).orElse(null);
        } catch (Exception e) {
            log.warn("[AssistantQuota] 套餐建议值查询闪断, 落到平台兜底 tenant={}", tenantId, e);
        }
        return resolveCreditLimit(
            q == null ? null : q.getCreditLimitOverride(),
            cfg.dailyCreditLimitPerUser(),
            planSuggested);
    }

    /**
     * 个人积分闸（额度三级管控批 / Q5=1 + Q9=2）：算上限 + 查今日用量，超了抛人话 429。
     *
     * <p>两个调用点共用这一条：对话进门（resolver）和每次调模型前（计费装饰器）——
     * 口径必须同一处，否则两处判定会漂移。侧车（起标题/出追问）走同一个装饰器，
     * 被拦时只是这次不生成标题，对话本身不受影响（那两处的异常本来就全吞）。</p>
     *
     * @param scene 只用于日志区分调用点（"进门" / "调模型前"）
     */
    public void assertPersonalCredits(String tenantId, Long userId, String scene) {
        dailyQuota.checkPersonalCredits(tenantId, userId, creditLimit(tenantId, userId).value(), scene);
    }

    public BillingAwareLimits billingAwareLimits(String tenantId, Long userId) {
        UserLimits ul = effectiveLimits(tenantId, userId);
        // dsflash P2-6：与 effectiveLimits 同款 fail-open——积分库闪断按钱包模式放行，
        // 不让三路消费方（resolver/status/websearch）跟着 500（宁可按默认放行不打断已过闸用户）
        java.util.Optional<com.smartdock.domain.AiCreditSubscription> sub;
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
        // 额度三级管控批（Q20=1）：联网次数改成「个人设的 ＞ 企业默认 ＞ 套餐档兜底」。
        // 旧口径是「企业填的正数一律被套餐档盖掉」（填 10 实际按 50 算），企业页那条说明条
        // 自己都写着「正数在积分态不影响实际限制」——这等于让自定义数字变成摆设。
        // 新口径：企业行存在（=企业配过）就用企业的值（含 -1 不限，可越过套餐档）；只有
        // 企业根本没配过行时，才拿套餐档当兜底。超管定的档位对订了套餐的企业不再有硬约束，
        // 这是已拍板接受的代价（平台少一道保险）。
        int web = ul.websearchLimit();
        boolean tenantConfigured = configService.effective(tenantId).rowPresent();
        if (web > 0 && !tenantConfigured) {
            try {
                web = creditService.subscriberWebsearchLimit(tenantId).orElse(web);
            } catch (Exception e) {
                log.warn("[AssistantQuota] 套餐联网档位查询闪断，沿用平台默认 tenant={}", tenantId, e);
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
        // 联网列批④落地：单人覆盖 > 租户 assistant_config.websearch_limit_per_user（平台默认 20）
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
     *
     * <p>额度三级管控批（2026-11-26）起多一维积分列，<b>不扩成 8 种组合</b>：积分列单独一条语句
     * （{@code upsertCreditOnly}），与上面那套正交组合；两条写同一个 (tenant,user) 行，
     * 故整段包进一个事务（原来只有一条语句，类上没有事务）。</p>
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void updateLimits(String tenantId, Long userId, Integer roundLimit, Integer websearchLimit,
                            Integer creditLimit) {
        // 校验器回合/联网共用（只收 1..10000）；积分列另给 -1（不限）/ -2（显式跟随）
        validateLimit(roundLimit, "回合上限");
        // 额度三级管控批（Q12=1）：联网列多收 -1（不限）——校验器按列拆开，回合列仍只收 1..10000
        // （-1 走到 AssistantDailyQuota.tryTurn 会被当成 0 急停抛 503，板上点名不能给）
        validateWebsearchLimit(websearchLimit);
        validateCreditLimit(creditLimit);
        boolean roundNull = roundLimit == null;
        boolean webNull = websearchLimit == null;
        if (roundNull && webNull) {
            quotaMapper.clearLimits(tenantId, userId);
        } else {
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
        // 积分列：null=不动（保持原值）、-2=写 NULL（清回跟随企业默认）、-1=不限、正数=上限。
        // 不传（null）就不发这条语句，避免每次改回合/联网都白写一次积分列
        if (creditLimit != null) {
            AssistantUserQuota cq = new AssistantUserQuota();
            cq.setTenantId(tenantId);
            cq.setUserId(userId);
            cq.setCreditLimitOverride(creditLimit);
            quotaMapper.upsertCreditOnly(cq);
        }
    }

    /** 兼容重载：只改回合/联网两列（积分列不动）。自调用不走事务代理——这条路径只有一条语句，无需事务 */
    public void updateLimits(String tenantId, Long userId, Integer roundLimit, Integer websearchLimit) {
        updateLimits(tenantId, userId, roundLimit, websearchLimit, null);
    }

    /** 积分列值域：正数 1..10000、-1=不限、-2=显式跟随（与回合/联网的 1..10000 分开，Q20=1） */
    private void validateCreditLimit(Integer limit) {
        if (limit == null || limit == -1 || limit == -2) {
            return;
        }
        if (limit < 1 || limit > 10000) {
            throw new ServiceException("积分上限须在 1~10000 之间，或选「不限」/「跟随企业默认」", 400);
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
        // 额度三级管控批（2026-11-26）：今日已花积分也一次聚合（与本月费用同一写法，换账本换窗口）。
        // 企业页「今日用量」列要显示「已花 / 上限」，逐行查会把一页变成 50 次查询
        Map<Long, Object> creditsMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (Map<String, Object> c : quotaMapper.selectTodayCreditsByUsers(tenantId, userIds)) {
                Object uid = c.get("userid");
                // create_by 可空的 DEDUCT 行（历史数据无法回填操作人）不归到任何人头上
                if (uid instanceof Number n) {
                    creditsMap.put(n.longValue(), c.get("creditsused"));
                }
            }
        }
        AssistantConfigService.EffectiveConfig cfg = configService.effective(tenantId);
        Integer planSuggested = planSuggestedOf(tenantId);
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
            // 额度三级管控批：三列——个人覆盖（null=跟随）、今天已花、生效上限 + 上限哪来的。
            // 生效上限用 creditLimit() 逐行算（它内部会再读一次覆盖行，但这页 ≤50 行且
            // 套餐建议值只查一次，代价可接受；换成批量查询反而要把四级链拆成两处，容易漂移）
            Object creditOverride = r.get("creditoverride");
            row.put("creditLimitOverride", creditOverride);
            row.put("creditsUsedToday", creditsMap.getOrDefault(uid, 0));
            CreditLimit cl = resolveCreditLimit(
                creditOverride == null ? null : ((Number) creditOverride).intValue(),
                cfg.dailyCreditLimitPerUser(),
                planSuggested);
            row.put("effectiveCreditLimit", cl.value());
            row.put("creditLimitSource", cl.source());
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

    /**
     * 联网列校验（Q12=1）：正数 1..10000，另收 <b>-1 = 不限</b>。
     * <b>回合列不走这个</b>——回合 -1 会被当成 0 急停（AssistantDailyQuota.tryTurn 见 limit&lt;=0 抛 503），
     * 板上点名「回合行不给不限」，所以校验器必须按列拆开。
     */
    private void validateWebsearchLimit(Integer limit) {
        if (limit == null) {
            return;
        }
        if (limit == AssistantWebSearchQuota.UNLIMITED_WEBSEARCH) {
            return;
        }
        if (limit < 1 || limit > 10000) {
            throw new ServiceException("联网次数上限须在 1~10000 之间，或选「不限」", 400);
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 套餐建议值（读失败按「没给建议」降级——企业页不能因为积分库闪断就整页 500） */
    private Integer planSuggestedOf(String tenantId) {
        try {
            return creditService.subscriberSuggestedDailyCredit(tenantId).orElse(null);
        } catch (Exception e) {
            log.warn("[AssistantQuota] 套餐建议值查询闪断, 企业页按平台兜底展示 tenant={}", tenantId, e);
            return null;
        }
    }
}
