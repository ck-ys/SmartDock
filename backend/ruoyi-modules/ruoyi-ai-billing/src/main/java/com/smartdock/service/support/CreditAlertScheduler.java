package com.smartdock.service.support;

import com.baomidou.lock.annotation.Lock4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.domain.DcsAiCreditSubscription;
import com.smartdock.mapper.DcsAiCreditSubscriptionMapper;
import com.smartdock.service.IDcsAiCreditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分套餐到期提醒 + 周池/加油包余量预警（AI 缺口批 Q1/Q2，拍板全A，2026-10-14）。
 *
 * <p>与 {@link WalletAlertScheduler} 同班车错峰：钱包 09:00 / 积分 09:10，同一套
 * WalletAlertEvent → outbox(dedupKey 幂等) 通知管道，<b>不建告警状态表</b>——判重全靠
 * businessDedupKey（对账告警同款），键设计即告警频次：</p>
 * <ul>
 *   <li>到期提醒（Q1，Coze 口径）：status=1 且剩余 ≤3 天 → 「即将到期」键
 *       creditSubExpiring:{subId}（每订阅一次）；到/过期 → 「今日/已到期」键
 *       creditSubExpired:{subId}（每订阅一次）——错过 3 天窗口补扫仍可达；</li>
 *   <li>周池用完（Q2）：键 creditWeekExhausted:{tenantId}:{weekStart} —— 每周最多一条，
 *       不逐日轰炸；仅 weeklyUsed&gt;0（真用过而非空池）且消费方式=CREDIT 才告；</li>
 *   <li>加油包将罀（Q2）：键 creditTankLow:{tenantId}:{yyyyMM} —— 每月最多一条。
 *       加油包无总量分母，拍板页「&lt;10%」口径落地为绝对阈值 {@link #TANK_LOW_THRESHOLD}
 *       =10 积分（1 积分=1 分钱）。</li>
 * </ul>
 *
 * <p>启动补跑复用钱包侧 shouldCatchUpNow 纯函数（同包）；per-订阅/per-租户异常只记堆栈
 * 不断当日扫描。@Lock4j 与启动补跑同键跨节点互斥。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreditAlertScheduler {

    /** 告警判重时区(与启动参数 -Duser.timezone=GMT+8 同口径) */
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    /** 到期前提醒窗口(天)：剩余 1~3 天发「即将到期」 */
    static final int EXPIRING_WINDOW_DAYS = 3;

    /** 加油包将罄绝对阈值(积分)：无总量分母，10% 口径落地取 10 积分 */
    static final long TANK_LOW_THRESHOLD = 10L;

    /** 积分套餐管理页(通知跳转) */
    private static final String REF_URL = "/tenant/ai-credit";

    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH_KEY = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter CN_DATE = DateTimeFormatter.ofPattern("M月d日");

    private final DcsAiCreditSubscriptionMapper subscriptionMapper;
    private final IDcsAiCreditService creditService;
    private final WalletAlertService walletAlertService;

    /** 自身代理(@Lazy 破循环依赖)：启动补跑与 09:10 tick 共用同一把 @Lock4j，与钱包侧同配方 */
    @Lazy
    @Autowired(required = false)
    private CreditAlertScheduler self;

    /** 每日 09:10 扫描(与钱包 09:00 错峰) */
    @Scheduled(cron = "0 10 9 * * ?")
    @Lock4j(keys = {"'credit-alert-daily-scan'"})
    public void scanCreditState() {
        scanAll("每日定时");
    }

    /** 启动补跑：已过扫描时刻则补扫一次(键幂等，与当日 tick 重叠不双发) */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (!WalletAlertScheduler.shouldCatchUpNow(LocalDateTime.now(BEIJING))) {
            return;
        }
        if (self != null) {
            self.scanNow("启动补跑");
        } else {
            scanAll("启动补跑");
        }
    }

    /** 扫描入口：与 09:10 tick 同键 @Lock4j 互斥 */
    @Lock4j(keys = {"'credit-alert-daily-scan'"})
    public void scanNow(String trigger) {
        scanAll(trigger);
    }

    private void scanAll(String trigger) {
        int alerted = alertExpiringSubscriptions();
        alerted += alertCreditPools();
        if (alerted > 0) {
            log.info("[CreditAlert] 扫描完成({}), 发送 {} 条积分侧告警", trigger, alerted);
        }
    }

    /**
     * Q1 到期提醒：status=1 且 end_date 在窗口内(剩余 ≤3 天或已过期未切换)。
     * 订阅成功/续订翻旧行为 status=0，故 status=1 且过期 = 尚无人触发懒切换，照告(兜底)。
     */
    private int alertExpiringSubscriptions() {
        // 窗口上界多留 1 天余量(按日历天判 daysLeft, 时间部分不参与)
        Date windowEnd = Date.from(LocalDate.now(BEIJING).plusDays(EXPIRING_WINDOW_DAYS + 1)
            .atStartOfDay(BEIJING).toInstant());
        List<DcsAiCreditSubscription> subs = subscriptionMapper.selectList(
            new LambdaQueryWrapper<DcsAiCreditSubscription>()
                .eq(DcsAiCreditSubscription::getStatus, "1")
                .le(DcsAiCreditSubscription::getEndTime, windowEnd));
        int alerted = 0;
        LocalDate today = LocalDate.now(BEIJING);
        for (DcsAiCreditSubscription sub : subs) {
            try {
                LocalDate endDate = LocalDate.ofInstant(sub.getEndTime().toInstant(), BEIJING);
                long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, endDate);
                String phase = classifyExpiry(daysLeft);
                if (phase == null) {
                    continue;
                }
                publishExpiryAlert(sub, phase, daysLeft, endDate);
                alerted++;
            } catch (Exception e) {
                log.error("[CreditAlert] 订阅 {} 到期告警处理失败, 跳过继续", sub.getId(), e);
            }
        }
        return alerted;
    }

    /** 纯函数(可测)：剩余天数 → EXPIRING(1~3) / EXPIRED(≤0) / null(>3 不告) */
    static String classifyExpiry(long daysLeft) {
        if (daysLeft >= 1 && daysLeft <= EXPIRING_WINDOW_DAYS) {
            return "EXPIRING";
        }
        if (daysLeft <= 0) {
            return "EXPIRED";
        }
        return null;
    }

    private void publishExpiryAlert(DcsAiCreditSubscription sub, String phase, long daysLeft, LocalDate endDate) {
        String endCn = endDate.format(CN_DATE);
        String title;
        String content;
        String dedupKey;
        if ("EXPIRING".equals(phase)) {
            title = "AI 积分套餐即将到期";
            content = "您订阅的「" + sub.getPlanName() + "」套餐将于 " + endCn + "（" + daysLeft
                + " 天后）到期，到期后自动切换为钱包按量计费；如需继续使用积分可提前续订。";
            dedupKey = "creditSubExpiring:" + sub.getId();
        } else if (daysLeft == 0) {
            title = "AI 积分套餐今日到期";
            content = "「" + sub.getPlanName() + "」套餐今日(" + endCn + ")到期，之后的对话将自动切换为钱包按量计费。";
            dedupKey = "creditSubExpired:" + sub.getId();
        } else {
            title = "AI 积分套餐已到期";
            content = "「" + sub.getPlanName() + "」套餐已于 " + endCn + " 到期，之后的对话将按钱包按量计费；可随时续订恢复积分模式。";
            dedupKey = "creditSubExpired:" + sub.getId();
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("subscriptionId", sub.getId());
        extra.put("planCode", sub.getPlanCode());
        extra.put("planName", sub.getPlanName());
        extra.put("endTime", sub.getEndTime());
        extra.put("daysLeft", daysLeft);
        walletAlertService.publishCreditAlert(sub.getTenantId(), "CREDIT_SUBSCRIPTION_EXPIRING",
            title, content, REF_URL, extra, dedupKey);
    }

    /**
     * Q2 周池/加油包余量预警：仅扫活跃订阅(未到期)租户，且消费方式=CREDIT
     * (钱包模式下积分未被消耗，余量状态对用户无操作意义，不告)。
     */
    private int alertCreditPools() {
        Date now = new Date();
        List<DcsAiCreditSubscription> active = subscriptionMapper.selectList(
            new LambdaQueryWrapper<DcsAiCreditSubscription>()
                .eq(DcsAiCreditSubscription::getStatus, "1")
                .gt(DcsAiCreditSubscription::getEndTime, now));
        int alerted = 0;
        for (DcsAiCreditSubscription sub : active) {
            String tenantId = sub.getTenantId();
            try {
                if (!"CREDIT".equals(creditService.consumeMode(tenantId))) {
                    continue;
                }
                IDcsAiCreditService.CreditSummary s = creditService.summary(tenantId);
                if (s == null || !s.subscribed()) {
                    continue;
                }
                // 周池用完：真用过(weeklyUsed>0)且余 0 —— 每周一告(键含 weekStart)
                if (s.weeklyRemaining() <= 0 && s.weeklyUsed() > 0) {
                    String weekKey = LocalDate.ofInstant(s.weekStart().toInstant(), BEIJING).format(DAY_KEY);
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("weeklyUsed", s.weeklyUsed());
                    extra.put("weeklyCap", s.weeklyCap());
                    extra.put("tankBalance", s.tankBalance());
                    walletAlertService.publishCreditAlert(tenantId, "CREDIT_WEEK_EXHAUSTED",
                        "AI 周池积分已用完",
                        "本周积分周池已用完（已用 " + s.weeklyUsed() + " / 上限 " + s.weeklyCap()
                            + "），加油包余额不受窗口限制仍可继续使用；周池将于下周一 0 点自动恢复。",
                        REF_URL, extra, "creditWeekExhausted:" + tenantId + ":" + weekKey);
                    alerted++;
                }
                // 加油包将罄：0 < 余额 < 阈值 —— 每月一告(键含月份)
                if (s.tankBalance() > 0 && s.tankBalance() < TANK_LOW_THRESHOLD) {
                    String monthKey = LocalDate.now(BEIJING).format(MONTH_KEY);
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("tankBalance", s.tankBalance());
                    extra.put("threshold", TANK_LOW_THRESHOLD);
                    walletAlertService.publishCreditAlert(tenantId, "CREDIT_TANK_LOW",
                        "AI 加油包余额将罄",
                        "加油包余额仅剩 " + s.tankBalance() + " 积分，用完后积分模式下的对话将被暂时拦截，"
                            + "请及时补充加油包或切换为钱包按量计费。",
                        REF_URL, extra, "creditTankLow:" + tenantId + ":" + monthKey);
                    alerted++;
                }
            } catch (Exception e) {
                // per-tenant 兜底：summary 走 ledger 现算，单租户失败(如库抖动)不拦其余租户
                log.error("[CreditAlert] 租户 {} 积分余量告警处理失败, 跳过继续", tenantId, e);
            }
        }
        return alerted;
    }
}
