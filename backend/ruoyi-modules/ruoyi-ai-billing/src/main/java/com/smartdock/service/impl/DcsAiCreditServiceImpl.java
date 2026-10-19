package com.smartdock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.DcsAiCreditLedger;
import com.smartdock.domain.DcsAiCreditPlan;
import com.smartdock.domain.DcsAiCreditSubscription;
import com.smartdock.domain.DcsTenantBillingMode;
import com.smartdock.domain.bo.DcsAiCreditPlanBo;
import com.smartdock.mapper.DcsAiCreditLedgerMapper;
import com.smartdock.mapper.DcsAiConfigMapper;
import com.smartdock.mapper.DcsAiCreditPlanMapper;
import com.smartdock.mapper.DcsAiCreditSubscriptionMapper;
import com.smartdock.mapper.DcsTenantBillingModeMapper;
import com.smartdock.service.IDcsAiCreditService;
import com.smartdock.service.support.TenantWalletService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 积分套餐服务实现（五需求批 R5）。表均为跨租户管理表（@InterceptorIgnore），
 * 全部谓词显式 tenantId；本实现自身也不依赖登录上下文（反应式线程可调）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DcsAiCreditServiceImpl implements IDcsAiCreditService {

    private final DcsAiCreditPlanMapper planMapper;
    private final DcsAiCreditSubscriptionMapper subscriptionMapper;
    private final DcsAiCreditLedgerMapper ledgerMapper;
    private final DcsTenantBillingModeMapper billingModeMapper;
    /** 业务批8 Q2：模型积分倍率表数据源 */
    private final DcsAiConfigMapper aiConfigMapper;
    /** 业务批8 Q4：订阅/加油包从租户钱包扣款（同事务条件扣减，余额不足人话 409） */
    private final TenantWalletService tenantWalletService;

    @Override
    public List<DcsAiCreditPlan> plans() {
        return TenantHelper.ignore(() -> planMapper.selectList(
            new LambdaQueryWrapper<DcsAiCreditPlan>()
                .eq(DcsAiCreditPlan::getEnabled, "1")
                .orderByAsc(DcsAiCreditPlan::getId)));
    }

    @Override
    public List<DcsAiCreditPlan> plansAll() {
        return TenantHelper.ignore(() -> planMapper.selectList(
            new LambdaQueryWrapper<DcsAiCreditPlan>()
                .orderByAsc(DcsAiCreditPlan::getId)));
    }

    @Override
    public Optional<DcsAiCreditSubscription> activeSubscription(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Optional.empty();
        }
        return TenantHelper.ignore(() -> {
            DcsAiCreditSubscription sub = subscriptionMapper.selectOne(
                new LambdaQueryWrapper<DcsAiCreditSubscription>()
                    .eq(DcsAiCreditSubscription::getTenantId, tenantId)
                    .eq(DcsAiCreditSubscription::getStatus, "1")
                    .gt(DcsAiCreditSubscription::getEndTime, new Date())
                    .orderByDesc(DcsAiCreditSubscription::getId)
                    .last("LIMIT 1"));
            return Optional.ofNullable(sub);
        });
    }

    @Override
    public CreditSummary summary(String tenantId) {
        Optional<DcsAiCreditSubscription> subOpt = activeSubscription(tenantId);
        // 业务批8 Q3：流水汇总条数据源——本周/本月消耗（DEDUCT 周池+加油包合计），
        // 未订阅也给值（订阅到期后的历史消耗仍要对账），与三本账同源现算
        Date weekStart = weekStart();
        Map<String, Object> weekSum = ledgerMapper.sumDeductSince(tenantId, weekStart);
        Map<String, Object> monthSum = ledgerMapper.sumDeductSince(tenantId, monthStart());
        long weekSpent = toLong(weekSum.get("weekly")) + toLong(weekSum.get("tank"));
        long monthSpent = toLong(monthSum.get("weekly")) + toLong(monthSum.get("tank"));
        if (subOpt.isEmpty()) {
            return new CreditSummary(false, null, null, null, 0, 0, 0, 0, 5, 0, weekStart, null,
                weekSpent, monthSpent, 0, 0);
        }
        DcsAiCreditSubscription sub = subOpt.get();
        DcsAiCreditPlan plan = TenantHelper.ignore(() -> planMapper.selectById(sub.getPlanId()));
        long weeklyCap = plan == null || plan.getWeeklyCredits() == null ? 0 : plan.getWeeklyCredits();
        int windowHours = plan == null || plan.getWindowHours() == null || plan.getWindowHours() <= 0
            ? 5 : plan.getWindowHours();
        long windowCap = plan == null || plan.getWindowCredits() == null ? 0 : plan.getWindowCredits();
        Map<String, Object> winSum = ledgerMapper.sumDeductSince(tenantId, windowStart(windowHours));
        long tankBalance = tankBalance(tenantId);
        // 业务反馈批1015b Q12：整周期总额口径——总量=显式 total_credits ?? 每周×周数；
        // 期内已用=订阅起算以来周池类消耗（加油包是独立池不掺和）。先软后硬：仅展示+预警
        int weeks = plan == null || plan.getDurationWeeks() == null || plan.getDurationWeeks() <= 0
            ? 12 : plan.getDurationWeeks();
        long totalQuota = plan != null && plan.getTotalCredits() != null && plan.getTotalCredits() > 0
            ? plan.getTotalCredits() : weeklyCap * weeks;
        long termUsed = toLong(ledgerMapper.sumDeductSince(tenantId, sub.getStartTime()).get("weekly"));
        return new CreditSummary(true, sub.getPlanCode(), sub.getPlanName(), sub.getEndTime(),
            toLong(weekSum.get("weekly")), weeklyCap,
            toLong(winSum.get("weekly")), windowCap, windowHours, tankBalance, weekStart,
            toDate(winSum.get("oldest")),
            weekSpent, monthSpent, totalQuota, termUsed);
    }

    @Override
    public GateResult preGate(String tenantId, long estimateCredits) {
        CreditSummary s = summary(tenantId);
        if (!s.subscribed()) {
            return GateResult.pass();
        }
        long weeklyRemaining = s.weeklyRemaining();
        long windowRoom = Math.max(0, s.windowCap() - s.windowUsed());
        boolean weeklyUsable = weeklyRemaining > 0 && windowRoom > 0;
        if (!weeklyUsable && s.tankBalance() >= estimateCredits) {
            return GateResult.pass();
        }
        if (!weeklyUsable && s.tankBalance() < estimateCredits) {
            if (weeklyRemaining <= 0) {
                long days = Math.max(1, Duration.between(LocalDateTime.now(), nextWeekStartLdt()).toDays() + 1);
                return new GateResult(true, "本周积分已用完（" + s.weeklyCap() + " 积分/周，约 "
                    + days + " 天后恢复）。两个立即可用的出口：管理员在「积分套餐」页购买加油包，"
                    + "或把消费方式切回钱包按量。");
            }
            // 三审回修（dspro P1-2）：恢复时长=窗口内最早一笔扣减老化出窗的时刻——
            // 原 minutesIntoWindow(now-windowStart) 恒等 windowHours*60，话术永远"约 5 分"
            long mins = Math.max(1, s.windowHours() * 60 - minutesSince(s.windowOldestAt()));
            String recover = mins >= 60
                ? "约 " + (mins / 60) + " 小时 " + (mins % 60) + " 分后自动恢复"
                : "约 " + mins + " 分后自动恢复";
            return new GateResult(true, "连续使用达到 " + s.windowHours() + " 小时窗口上限（"
                + s.windowCap() + " 积分），" + recover + "；购买加油包可立即继续（加油包不受窗口限制）。");
        }
        return GateResult.pass();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deduct(String tenantId, long credits, String scene, String idempotencyKey, Long operatorId) {
        if (credits <= 0 || tenantId == null) {
            return;
        }
        CreditSummary s = summary(tenantId);
        if (!s.subscribed()) {
            // 六审回修（dspro P0-1/k3 P1-2）：闸时订阅、落账时已失效/被退订——usage 单据按
            // credits 口径照记（钱包不动账），本笔两不扣。留 WARN 供对账巡检（登记权衡：
            // 不追补扣钱包，避免同笔双路径计费）
            log.warn("[AiCredit] 落账时订阅已失效, 本笔零计费留痕: tenant={} credits={} key={}",
                tenantId, credits, idempotencyKey);
            return;
        }
        long weeklyRoom = Math.min(s.weeklyRemaining(), Math.max(0, s.windowCap() - s.windowUsed()));
        long fromWeekly = Math.min(credits, Math.max(0, weeklyRoom));
        long fromTank = Math.min(credits - fromWeekly, s.tankBalance());
        DcsAiCreditLedger row = new DcsAiCreditLedger();
        row.setId(IdWorker.getId());
        row.setTenantId(tenantId);
        row.setBizType("DEDUCT");
        row.setCredits((int) (fromWeekly + fromTank));
        row.setWeeklyCredits((int) fromWeekly);
        row.setTankCredits((int) fromTank);
        row.setScene(scene);
        // 六审回修（dspro P1-1/k3 P1-1）：余量不足被夹逼时差额此前静默免单——remark 留缺口
        // 供管理页/对账巡检（无行锁权衡下并发窗口也会放大缺口，至少可见）
        if (fromWeekly + fromTank < credits) {
            row.setRemark("缺口 " + (credits - fromWeekly - fromTank) + " 积分（请求 " + credits + "，余量仅 "
                + (fromWeekly + fromTank) + "）");
            log.warn("[AiCredit] 积分余量不足部分扣减: tenant={} 请求={} 实扣={} key={}",
                tenantId, credits, fromWeekly + fromTank, idempotencyKey);
        }
        row.setIdempotencyKey(idempotencyKey);
        // 业务批8 Q6（dspro P1-1 回修）：消耗人落 create_by——双视角流水三件套的数据源；
        // 自定义 @Insert 不吃 MP 自动填充，列清单与实体必须显式带（operatorId 由计费
        // 装饰器从会话用户传入；侧车/无登录上下文时为 null，展示「--」）
        row.setCreateBy(operatorId);
        // 三审回修（dspro P2-3）：DEDUCT 行时间显式取 App 时钟与窗口/周界判定同源——
        // 原 DB 默认时钟与 JVM 实测差 ~39s，JVM/DB 时区漂移时窗口会被拉长/平移
        row.setCreateTime(new Date());
        // 六审回修（dspro P0-1 方案A）：本方法已并入 usage 落账同一事务（经 AiBillingService
        // recordTransactionalCredits 调入），插入改 ON CONFLICT 消化重放——同事务内再
        // catch(DuplicateKeyException) 会把 PG 事务置 aborted 连累 usage 一起回滚
        int inserted = TenantHelper.ignore(() -> ledgerMapper.insertIgnoreIdem(row));
        if (inserted == 0) {
            log.info("[AiCredit] 幂等键重放跳过(ON CONFLICT 吸收): key={}", idempotencyKey);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DcsAiCreditSubscription subscribe(String tenantId, String planCode, Long operatorId, boolean grant) {
        DcsAiCreditPlan plan = requirePlan(planCode, "SUBSCRIBE");
        // 三审回修（m3 P0/dspro P1-1/glm53 P2 三席合议）：旧 active 行批量翻 0（UPDATE 而非
        // selectOne+updateById——双 active 残留时 selectOne 抛 TooManyResults 使订阅永久 500）；
        // 并发第二条由部分唯一索引 uk_credit_sub_active 兜底撞键，翻友好话术
        TenantHelper.ignore(() -> subscriptionMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DcsAiCreditSubscription>()
                .eq(DcsAiCreditSubscription::getTenantId, tenantId)
                .eq(DcsAiCreditSubscription::getStatus, "1")
                .set(DcsAiCreditSubscription::getStatus, "0")));
        int weeks = plan.getDurationWeeks() == null || plan.getDurationWeeks() <= 0 ? 12 : plan.getDurationWeeks();
        Date now = new Date();
        DcsAiCreditSubscription sub = new DcsAiCreditSubscription();
        sub.setId(IdWorker.getId());
        sub.setTenantId(tenantId);
        sub.setPlanId(plan.getId());
        sub.setPlanCode(plan.getPlanCode());
        sub.setPlanName(plan.getPlanName());
        sub.setStartTime(now);
        sub.setEndTime(Date.from(now.toInstant().atZone(ZoneId.systemDefault())
            .toLocalDateTime().plusWeeks(weeks).atZone(ZoneId.systemDefault()).toInstant()));
        sub.setStatus("1");
        sub.setCreateBy(operatorId);
        sub.setCreateTime(now);
        try {
            TenantHelper.ignore(() -> subscriptionMapper.insert(sub));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.warn("[AiCredit] 并发订阅撞唯一索引(另一订阅刚生效): tenant={} plan={}", tenantId, planCode);
            throw new ServiceException("已有订阅刚刚生效（可能是其他管理员同时操作），请刷新后查看当前订阅。", 409);
        }
        // 业务批8 Q7 丁：先切积分开关再扣款（m3 P1-3 回修）——续订与到期回写并发时，
        // 若扣款后才 upsert，中间窗口装饰器可能读到 WALLET 走钱包路径扣钱；同事务内
        // 顺序对调后任一并发时刻读到的开关都与最终态一致，扣款失败整体回滚无副作用
        billingModeMapper.upsertMode(tenantId, "CREDIT", operatorId, new Date());
        // 业务批8 Q4：订阅从租户钱包扣款（grant=true 赠送开通不扣，仅超管可用——Controller 侧拦）
        if (grant) {
            log.info("[AiCredit] 赠送开通(不扣钱包): tenant={} plan={} operator={}", tenantId, planCode, operatorId);
        } else {
            chargeWallet(tenantId, plan, "AI_CREDIT_SUBSCRIBE", sub.getId(), "订阅 " + plan.getPlanName());
        }
        ledgerRow(tenantId, "SUBSCRIBE", 0, "订阅 " + plan.getPlanName()
            + "（" + plan.getWeeklyCredits() + " 积分/周 × " + weeks + " 周）"
            + (grant ? "【赠送开通】" : "【已扣钱包 " + plan.getPriceYuan().stripTrailingZeros().toPlainString() + " 元】"), operatorId);
        return sub;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void boost(String tenantId, String planCode, Long operatorId, boolean grant) {
        DcsAiCreditPlan plan = requirePlan(planCode, "BOOST");
        if (plan.getWeeklyCredits() != null && plan.getWeeklyCredits() > 0) {
            // BOOST 档的积分量借用 weekly_credits 列——语义在 remark 里说人话
        }
        long credits = plan.getWeeklyCredits() == null ? 0 : plan.getWeeklyCredits();
        if (credits <= 0) {
            throw new ServiceException("该加油包未配置积分量", 400);
        }
        // dspro P2-1：先落 BOOST 入账行、钱包扣款 biz_id 指向该行 id——对账④可按 biz_id
        // 关联「扣了钱」与「入了账」（此前临时雪花号全库无对应单据）；扣款失败同事务整笔回滚
        Long boostLedgerId = ledgerRow(tenantId, "BOOST", credits, "购买加油包 " + plan.getPlanName()
            + (grant ? "【赠送】" : "【已扣钱包 " + plan.getPriceYuan().stripTrailingZeros().toPlainString() + " 元】"), operatorId);
        if (grant) {
            log.info("[AiCredit] 赠送加油包(不扣钱包): tenant={} plan={} operator={}", tenantId, planCode, operatorId);
        } else {
            chargeWallet(tenantId, plan, "AI_CREDIT_BOOST", boostLedgerId, "购买加油包 " + plan.getPlanName());
        }
    }

    /** 订阅/加油包购买扣款（业务批8 Q4）：同事务条件扣减，余额不足整笔回滚 + 人话 409 */
    private void chargeWallet(String tenantId, DcsAiCreditPlan plan, String serviceCode, Long bizId, String action) {
        BigDecimal price = plan.getPriceYuan() == null ? BigDecimal.ZERO : plan.getPriceYuan();
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        try {
            tenantWalletService.deductForAiUsageJoinTx(tenantId, price.setScale(4, RoundingMode.HALF_UP),
                serviceCode, bizId, "credit-plan:" + tenantId + ":" + bizId);
        } catch (ServiceException e) {
            BigDecimal avail = tenantWalletService.availableBalanceOf(tenantId);
            // m3 P2-3 回修：warn 带原异常栈（底层条件扣减路径可追溯），用户侧只看人话 409
            log.warn("[AiCredit] 钱包余额不足: tenant={} plan={} need={} avail={}",
                tenantId, plan.getPlanCode(), price, avail, e);
            throw new ServiceException(action + "需 " + price.stripTrailingZeros().toPlainString()
                + " 元，租户钱包当前可用 " + avail + " 元——请先充值，或由超级管理员赠送开通。", 409);
        }
    }

    @Override
    public java.util.Optional<Integer> subscriberWebsearchLimit(String tenantId) {
        return activeSubscription(tenantId).map(sub -> {
            DcsAiCreditPlan plan = TenantHelper.ignore(() -> planMapper.selectById(sub.getPlanId()));
            Integer limit = plan == null ? null : plan.getWebsearchLimitPerUserDaily();
            return (limit != null && limit > 0) ? limit : null;
        });
    }

    @Override
    public org.dromara.common.mybatis.core.page.TableDataInfo<DcsAiCreditLedger> ledgerPage(
        String tenantId, String bizType, String beginTime, String endTime, Long filterUserId,
        org.dromara.common.mybatis.core.page.PageQuery pageQuery) {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<DcsAiCreditLedger> page =
            TenantHelper.ignore(() -> ledgerMapper.selectPage(pageQuery.build(),
                ledgerWrapper(tenantId, bizType, beginTime, endTime, filterUserId)));
        fillOperatorNames(page.getRecords());
        return org.dromara.common.mybatis.core.page.TableDataInfo.build(page);
    }

    /**
     * AI 缺口批 Q7（2026-10-14）：流水导出列表（与 ledgerPage 同构，无分页，上限 1 万行）。
     */
    @Override
    public List<DcsAiCreditLedger> ledgerList(String tenantId, String bizType,
                                              String beginTime, String endTime, Long filterUserId) {
        List<DcsAiCreditLedger> rows = TenantHelper.ignore(() ->
            ledgerMapper.selectList(ledgerWrapper(tenantId, bizType, beginTime, endTime, filterUserId)
                .last("LIMIT 10000")));
        fillOperatorNames(rows);
        return rows;
    }

    /**
     * 流水查询公共包装器（ledgerPage/ledgerList 同口径）。
     * dspro P0-1 回修：PG 不做 varchar 与 timestamp 隐式比较（连接串无 stringtype=
     * unspecified，String 绑定 100% 42883）——边界统一转 Date；上界用次日 00:00 半开
     * 区间（23:59:59 闭区间会漏 .001~.999 的行），与 DcsNoticeServiceImpl:288 既有纪律同口径。
     */
    private LambdaQueryWrapper<DcsAiCreditLedger> ledgerWrapper(String tenantId, String bizType,
                                                                String beginTime, String endTime,
                                                                Long filterUserId) {
        java.util.Date begin = parseDay(beginTime, false);
        java.util.Date endExcl = parseDay(endTime, true);
        return new LambdaQueryWrapper<DcsAiCreditLedger>()
            .eq(DcsAiCreditLedger::getTenantId, tenantId)
            .eq(bizType != null && !bizType.isBlank(), DcsAiCreditLedger::getBizType, bizType)
            .eq(filterUserId != null, DcsAiCreditLedger::getCreateBy, filterUserId)
            .ge(begin != null, DcsAiCreditLedger::getCreateTime, begin)
            .lt(endExcl != null, DcsAiCreditLedger::getCreateTime, endExcl)
            // 六审回修（glm53flash 视觉）：按业务时间倒序（id 仅次序稳定器）——
            // 补数据/时钟修正场景 create_time 与落库雪花序脱节，对账视图会跳行
            .orderByDesc(DcsAiCreditLedger::getCreateTime)
            .orderByDesc(DcsAiCreditLedger::getId);
    }

    @Override
    public List<Map<String, Object>> ledgerOperators(String tenantId) {
        // 近 90 天有流水的操作人（下拉数据源；90 天外的旧人靠筛选不影响主流程）
        Date since = Date.from(java.time.LocalDateTime.now().minusDays(90)
            .atZone(ZoneId.systemDefault()).toInstant());
        List<Long> ids = ledgerMapper.distinctOperators(tenantId, since);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = nickNameMap(ids);
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Long id : ids) {
            Map<String, Object> m = new HashMap<>();
            m.put("userId", id);
            m.put("nickName", names.getOrDefault(id, "用户 " + id));
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> modelRates(String tenantId) {
        // 业务批8 Q2：当前租户在用 CHAT 模型的倍率表。预估积分/轮 = 实测基准 0.65 积分/轮
        //（dsv4.1fh 无倍率时代实测均值 1.3 的折半）× 倍率，一位小数展示；免费档倍率无意义置 0
        List<com.smartdock.domain.DcsAiConfig> configs = TenantHelper.ignore(() ->
            aiConfigMapper.selectList(new LambdaQueryWrapper<com.smartdock.domain.DcsAiConfig>()
                .eq(com.smartdock.domain.DcsAiConfig::getTenantId, tenantId)
                .eq(com.smartdock.domain.DcsAiConfig::getUseType, "CHAT")
                .eq(com.smartdock.domain.DcsAiConfig::getEnabled, "1")));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.smartdock.domain.DcsAiConfig c : configs) {
            Map<String, Object> m = new HashMap<>();
            m.put("configId", c.getConfigId());
            m.put("provider", c.getProvider());
            m.put("modelName", c.getModelName());
            m.put("multiplier", c.getCreditMultiplier() == null ? 1 : c.getCreditMultiplier());
            boolean free = c.getUnitPricePerCall() != null && c.getUnitPricePerCall().signum() == 0
                && "FIXED_PER_CALL".equals(c.getBillingMode());
            m.put("free", free);
            m.put("perRound", free ? 0
                : BigDecimal.valueOf(0.65)
                    .multiply(c.getCreditMultiplier() == null ? BigDecimal.ONE : c.getCreditMultiplier())
                    .setScale(1, RoundingMode.HALF_UP).doubleValue());
            m.put("isDefault", "1".equals(c.getIsDefault()));
            out.add(m);
        }
        return out;
    }

    /** 行内回填消耗人昵称（一次批量查，空行跳过） */
    private void fillOperatorNames(List<DcsAiCreditLedger> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(DcsAiCreditLedger::getCreateBy)
            .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, String> names = nickNameMap(ids);
        for (DcsAiCreditLedger row : rows) {
            if (row.getCreateBy() != null) {
                row.setOperatorName(names.getOrDefault(row.getCreateBy(), "用户 " + row.getCreateBy()));
            }
        }
    }

    private Map<Long, String> nickNameMap(Collection<Long> ids) {
        List<Map<String, Object>> rows = ledgerMapper.selectNickNames(ids);
        Map<Long, String> out = new HashMap<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                if (r.get("userId") instanceof Number n && r.get("nickName") instanceof String s) {
                    out.put(n.longValue(), s);
                }
            }
        }
        return out;
    }

    /** yyyy-MM-dd → 当日 00:00（next=true 时次日 00:00，半开区间上界）；非法格式给 400 人话而非 PG 500。
     * 日界钉 Asia/Shanghai（与 AssistantDailyQuota 同口径，不随启动参数漂移——dsflash P2-5）。 */
    private static java.util.Date parseDay(String day, boolean next) {
        if (day == null || day.isBlank()) {
            return null;
        }
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(day.trim());
            java.time.LocalDateTime t = next ? d.plusDays(1).atStartOfDay() : d.atStartOfDay();
            return java.util.Date.from(t.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant());
        } catch (java.time.format.DateTimeParseException e) {
            throw new org.dromara.common.core.exception.ServiceException("时间格式须为 yyyy-MM-dd", 400);
        }
    }

    @Override
    public List<DcsAiCreditLedger> recent(String tenantId, int limit) {
        return ledgerMapper.recentOf(tenantId, Math.min(Math.max(limit, 1), 50));
    }

    @Override
    public String consumeMode(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return "WALLET";
        }
        DcsTenantBillingMode row = billingModeMapper.selectByTenant(tenantId);
        if (row == null || !"CREDIT".equals(row.getConsumeMode())) {
            return "WALLET";
        }
        if (activeSubscription(tenantId).isEmpty()) {
            // 业务批8 Q7 丁：订阅到期自动切回钱包按量。dspro P1-2 回修：判定下沉到
            // revertToWalletIfStillExpired 条件写（NOT EXISTS 生效订阅才回写）——消灭
            // 「读到过期→并发 subscribe 刚提交 CREDIT→盲写覆盖回 WALLET 且不再自愈」竞态；
            // affected=0（续订已抢跑）时保留 CREDIT 且照常返回积分模式，两种交错都收敛
            int reverted = billingModeMapper.revertToWalletIfStillExpired(tenantId, new Date());
            if (reverted > 0) {
                // m3 P2-2 回修：自动切换与手动切换同样落 ADJUST 流水（审计对称，操作人=系统）
                ledgerRow(tenantId, "ADJUST", 0, "订阅到期，消费方式自动切回钱包按量", null);
                log.info("[AiCredit] 订阅到期, 消费方式自动切回钱包按量: tenant={}", tenantId);
                return "WALLET";
            }
            // 条件写 0 行=并发续订已生效：按积分模式走（下回合读到 CREDIT+新订阅）
            return "CREDIT";
        }
        return "CREDIT";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setConsumeMode(String tenantId, String mode, Long operatorId) {
        if (!"WALLET".equals(mode) && !"CREDIT".equals(mode)) {
            throw new ServiceException("消费方式只能是 WALLET（钱包按量）或 CREDIT（积分订阅）", 400);
        }
        if ("CREDIT".equals(mode) && activeSubscription(tenantId).isEmpty()) {
            throw new ServiceException("当前没有生效的积分订阅，不能切到积分消费——请先在「积分套餐」页订阅。", 409);
        }
        billingModeMapper.upsertMode(tenantId, mode, operatorId, new Date());
        ledgerRow(tenantId, "ADJUST", 0, "消费方式切换为 " + ("CREDIT".equals(mode) ? "积分订阅" : "钱包按量"), operatorId);
    }

    @Override
    public Map<String, Long> activeTenantCountByPlan() {
        List<Map<String, Object>> rows = TenantHelper.ignore(() -> subscriptionMapper.selectMaps(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DcsAiCreditSubscription>()
                .select("plan_code", "COUNT(DISTINCT tenant_id) AS cnt")
                .eq("status", "1")
                .gt("end_time", new Date())
                .groupBy("plan_code")));
        Map<String, Long> out = new HashMap<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                Object code = r.get("plan_code");
                Object cnt = r.get("cnt");
                if (code instanceof String s && cnt instanceof Number n) {
                    out.put(s, n.longValue());
                }
            }
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DcsAiCreditPlan savePlan(DcsAiCreditPlanBo bo, Long operatorId) {
        if (bo.getId() == null) {
            boolean exists = TenantHelper.ignore(() -> planMapper.selectCount(
                new LambdaQueryWrapper<DcsAiCreditPlan>().eq(DcsAiCreditPlan::getPlanCode, bo.getPlanCode()))) > 0;
            if (exists) {
                throw new ServiceException("套餐编码已存在: " + bo.getPlanCode(), 409);
            }
            DcsAiCreditPlan plan = new DcsAiCreditPlan();
            plan.setId(IdWorker.getId());
            applyPlanFields(plan, bo, true);
            plan.setEnabled("1");
            TenantHelper.ignore(() -> planMapper.insert(plan));
            log.info("[AiCredit] 新增套餐: code={} name={} operator={}", bo.getPlanCode(), bo.getPlanName(), operatorId);
            return plan;
        }
        DcsAiCreditPlan plan = TenantHelper.ignore(() -> planMapper.selectById(bo.getId()));
        if (plan == null) {
            throw new ServiceException("套餐不存在: " + bo.getId(), 404);
        }
        applyPlanFields(plan, bo, false);
        TenantHelper.ignore(() -> planMapper.updateById(plan));
        log.info("[AiCredit] 编辑套餐: code={} name={} operator={}", plan.getPlanCode(), bo.getPlanName(), operatorId);
        return plan;
    }

    /**
     * BO 字段落实体。planCode/kind 是技术键：新增按 BO 落；编辑忽略（改 code 会让存量
     * 订阅行的 plan_code 断链、改 kind 会漂移扣费语义——前端编辑表单两字段只读）。
     * 窗口两参数允许 null：编辑=保旧值，新增=窗口时长 5h、窗口上限取周池 20%。
     */
    private void applyPlanFields(DcsAiCreditPlan plan, DcsAiCreditPlanBo bo, boolean isNew) {
        if (isNew) {
            plan.setPlanCode(bo.getPlanCode());
            plan.setKind(bo.getKind());
        }
        plan.setPlanName(bo.getPlanName());
        plan.setPriceYuan(bo.getPriceYuan());
        plan.setWeeklyCredits(bo.getWeeklyCredits());
        plan.setWindowCredits(bo.getWindowCredits() != null ? bo.getWindowCredits()
            : (isNew ? Math.max(0, bo.getWeeklyCredits() / 5) : plan.getWindowCredits()));
        plan.setWindowHours(bo.getWindowHours() != null ? bo.getWindowHours()
            : (isNew ? 5 : plan.getWindowHours()));
        plan.setDurationWeeks(bo.getDurationWeeks() != null ? bo.getDurationWeeks()
            : (isNew ? 12 : plan.getDurationWeeks()));
        plan.setWebsearchLimitPerUserDaily(bo.getWebsearchLimitPerUserDaily() != null
            ? bo.getWebsearchLimitPerUserDaily() : (isNew ? 0 : plan.getWebsearchLimitPerUserDaily()));
        plan.setDescription(bo.getDescription());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePlanStatus(Long planId, String enabled, Long operatorId) {
        if (!"0".equals(enabled) && !"1".equals(enabled)) {
            throw new ServiceException("状态只能是 0（下架）或 1（在售）", 400);
        }
        DcsAiCreditPlan plan = TenantHelper.ignore(() -> planMapper.selectById(planId));
        if (plan == null) {
            throw new ServiceException("套餐不存在: " + planId, 404);
        }
        plan.setEnabled(enabled);
        TenantHelper.ignore(() -> planMapper.updateById(plan));
        log.info("[AiCredit] 套餐{}: code={} operator={}", "1".equals(enabled) ? "上架" : "下架", plan.getPlanCode(), operatorId);
    }

    /* ================= 内部 ================= */

    /** @return 行 id（dspro P2-1：钱包流水 biz_id 溯源锚——boost 扣款与本行共用同一 id） */
    private Long ledgerRow(String tenantId, String bizType, long credits, String remark, Long operatorId) {
        DcsAiCreditLedger row = new DcsAiCreditLedger();
        row.setId(IdWorker.getId());
        row.setTenantId(tenantId);
        row.setBizType(bizType);
        row.setCredits((int) credits);
        row.setWeeklyCredits(0);
        row.setTankCredits(0);
        row.setRemark(remark);
        row.setCreateBy(operatorId);
        row.setCreateTime(new Date());
        TenantHelper.ignore(() -> ledgerMapper.insert(row));
        return row.getId();
    }

    private DcsAiCreditPlan requirePlan(String planCode, String kind) {
        DcsAiCreditPlan plan = TenantHelper.ignore(() -> planMapper.selectOne(
            new LambdaQueryWrapper<DcsAiCreditPlan>()
                .eq(DcsAiCreditPlan::getPlanCode, planCode)
                .eq(DcsAiCreditPlan::getEnabled, "1")));
        if (plan == null) {
            throw new ServiceException("套餐不存在或已下架: " + planCode, 404);
        }
        if (!kind.equals(plan.getKind())) {
            throw new ServiceException("该操作只接受 " + ("SUBSCRIBE".equals(kind) ? "订阅档" : "加油包") + ": " + planCode, 400);
        }
        return plan;
    }

    private long tankBalance(String tenantId) {
        Map<String, Object> weekSum = ledgerMapper.sumDeductSince(tenantId, new Date(0));
        return Math.max(0, ledgerMapper.sumBoostGranted(tenantId) - toLong(weekSum.get("tank")));
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static Date toDate(Object v) {
        return v instanceof java.sql.Timestamp t ? new Date(t.getTime()) : (v instanceof Date d ? d : null);
    }

    /** 自 from 起经过的分钟数（null 视为刚发生=0） */
    private static long minutesSince(Date from) {
        if (from == null) {
            return 0;
        }
        return Math.max(0, Duration.between(from.toInstant(), java.time.Instant.now()).toMinutes());
    }

    /** 本周一 00:00 */
    static Date weekStart() {
        LocalDateTime now = LocalDateTime.now();
        return Date.from(now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /** 本月 1 日 00:00（业务批8 Q3：流水汇总条「本月消耗」口径） */
    static Date monthStart() {
        return Date.from(LocalDateTime.now().withDayOfMonth(1).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /** 下周一 00:00 */
    static Date nextWeekStart() {
        return Date.from(nextWeekStartLdt().atZone(ZoneId.systemDefault()).toInstant());
    }

    static LocalDateTime nextWeekStartLdt() {
        return LocalDateTime.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toLocalDate().atStartOfDay();
    }

    static Date windowStart(int hours) {
        return Date.from(LocalDateTime.now().minusHours(hours)
            .atZone(ZoneId.systemDefault()).toInstant());
    }
}
