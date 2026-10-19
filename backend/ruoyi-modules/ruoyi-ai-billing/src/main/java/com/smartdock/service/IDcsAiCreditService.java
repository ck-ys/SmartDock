package com.smartdock.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import com.smartdock.domain.DcsAiCreditLedger;
import com.smartdock.domain.DcsAiCreditPlan;
import com.smartdock.domain.DcsAiCreditSubscription;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * AI 积分套餐服务（五需求批 R5 / Q8-Q12 拍板A，2026-10-13）。
 *
 * <p>口径：1 积分=1 分钱；订阅租户的助手对话走积分（钱包不动账）；周池每周一 0 点
 * 恢复，5 小时滚动窗口防刷（窗口只限周池扣减，加油包余额不受限）；池尽 tank 尽=拦截
 * +人话话术。周池/窗口用量=ledger 现算 SUM（唯一真源，无 Redis 漂移/重建逻辑）。</p>
 *
 * <p>并发口径（与钱包条件扣减的差异，有意取舍）：gate 判定与 deduct 落账之间存在
 * 窗口期，并发回合可能小幅超扣周池——下回合闸立即拦住，不做行锁串行（对话 QPS 低、
 * 超扣上限=单回合成本，风险可承受；钱包资损路径不受本服务影响）。</p>
 */
public interface IDcsAiCreditService {

    /** 在售档位（SUBSCRIBE + BOOST，enabled=1） */
    List<DcsAiCreditPlan> plans();

    /** 全量档位（含下架行，超管管理视角——下架后仍可见可再上架；step5 P2-5） */
    List<DcsAiCreditPlan> plansAll();

    /** 生效订阅（status=1 且 end_time>now）；无=Optional.empty（走钱包计费） */
    Optional<DcsAiCreditSubscription> activeSubscription(String tenantId);

    /**
     * 用量与余额快照（周池/窗口/加油包三本账 + 生效订阅信息）。
     * summary.subscribed=false 时三本账无意义（不订阅不扣积分）。
     */
    CreditSummary summary(String tenantId);

    /**
     * 前置闸（模型调用前）：可消耗=通过；池尽或窗口顶格且无加油包余额=拦截。
     * estimateCredits=本回合估算积分（≥1），只用于判断门槛不预留。
     */
    GateResult preGate(String tenantId, long estimateCredits);

    /**
     * 落账一笔消耗：优先周池（受窗口余量约束），溢出走加油包余额；幂等键防重放
     * 双扣。闸后竞态导致的少量缺口照实落（下回合闸兜底）。失败抛异常由调用方决定
     * 是否吞（计费装饰器只记日志不吞会话）。
     * 业务批8 Q6（dspro P1-1 回修）：operatorId=消耗人，落 create_by——
     * 「只看我的/消耗人列/按人筛选」三件套的数据源；自定义 @Insert 不吃 MP 自动填充，
     * 必须显式传。存量历史 DEDUCT 行无 create_by（无法回填，usage 侧无关联键），
     * 展示为「--」如实保留。
     */
    void deduct(String tenantId, long credits, String scene, String idempotencyKey, Long operatorId);

    /**
     * 管理端订阅/续订某档位（旧生效行翻 0，新行 start=now，end=now+duration 周）。
     * 业务批8 Q4：grant=false 从租户钱包扣款（余额不足 409 人话整笔回滚）；grant=true
     * 赠送开通不扣钱（仅超管可用，Controller 侧拦截）。订阅成功自动把消费方式切 CREDIT。
     */
    DcsAiCreditSubscription subscribe(String tenantId, String planCode, Long operatorId, boolean grant);

    /** 管理端购买加油包（BOOST 档，credits 入余额）；grant 口径同 subscribe */
    void boost(String tenantId, String planCode, Long operatorId, boolean grant);

    /**
     * 业务批7 Q1：订阅租户的联网次数档位（dcs_ai_credit_plan.websearch_limit_per_user_daily）。
     * 空=未订阅或套餐未配档位（调用方沿用租户默认口径）。
     */
    Optional<Integer> subscriberWebsearchLimit(String tenantId);

    /**
     * 流水真分页（业务批7 Q4）：bizType/beginTime/endTime(yyyy-MM-dd) 可选过滤，
     * 时间按当天 00:00:00~23:59:59 闭区间展开。
     * 业务批8 Q6：filterUserId 非空=按人筛选/只看我的（create_by=该用户），行回填 operatorName 昵称。
     */
    TableDataInfo<DcsAiCreditLedger> ledgerPage(String tenantId, String bizType,
                                                String beginTime, String endTime, Long filterUserId,
                                                PageQuery pageQuery);

    /**
     * AI 缺口批 Q7（2026-10-14）：流水导出列表——与 ledgerPage 同筛选同排序，无分页，
     * 上限 1 万行防呆；行回填 operatorName 昵称。
     */
    List<DcsAiCreditLedger> ledgerList(String tenantId, String bizType,
                                       String beginTime, String endTime, Long filterUserId);

    /** 业务批8 Q6：近 90 天该租户有流水的操作人（userId+昵称，按人筛选下拉） */
    List<java.util.Map<String, Object>> ledgerOperators(String tenantId);

    /**
     * 业务批8 Q2：当前租户在用 CHAT 模型的积分倍率表（含预估积分/轮）——
     * 管理页「模型积分倍率」卡数据源，倍率变更影响订阅租户的每回合扣分。
     */
    List<java.util.Map<String, Object>> modelRates(String tenantId);

    /** 用户侧近 N 条（明细弹层） */
    List<DcsAiCreditLedger> recent(String tenantId, int limit);

    /**
     * 租户消费方式（业务批8 Q7 丁）：WALLET=钱包按量（无行默认）/ CREDIT=积分订阅。
     * 派发口径：行=CREDIT 但生效订阅已到期时，本调用顺手回写 WALLET 并返回 WALLET
     * （订阅到期自动切回）——返回 CREDIT 即保证有生效订阅，调用方可据此省一次订阅查询。
     */
    String consumeMode(String tenantId);

    /**
     * 显式切换消费方式：CREDIT 前置校验有生效订阅（否则 409 人话）；WALLET 直切。
     * 订阅成功路径由 subscribe() 内部自动切 CREDIT，不走本方法。
     */
    void setConsumeMode(String tenantId, String mode, Long operatorId);

    /** 各档位当前生效订阅租户数（管理页改价影响提示：planCode → count） */
    java.util.Map<String, Long> activeTenantCountByPlan();

    /** 档位新增/编辑（业务批8 Q4：任何参数可改；id 空=新增） */
    DcsAiCreditPlan savePlan(com.smartdock.domain.bo.DcsAiCreditPlanBo bo, Long operatorId);

    /** 档位上下架（enabled 1/0） */
    void changePlanStatus(Long planId, String enabled, Long operatorId);

    record CreditSummary(boolean subscribed, String planCode, String planName, Date endTime,
                         long weeklyUsed, long weeklyCap, long windowUsed, long windowCap,
                         int windowHours, long tankBalance, Date weekStart, Date windowOldestAt,
                         long weekSpent, long monthSpent,
                         long totalQuota, long termUsed) {

        public long weeklyRemaining() {
            return Math.max(0, weeklyCap - weeklyUsed);
        }

        /** 业务反馈批1015b Q12：整周期总额剩余（先软后硬——只展示/预警，不参与扣费闸） */
        public long totalRemaining() {
            return Math.max(0, totalQuota - termUsed);
        }
    }

    record GateResult(boolean blocked, String message) {

        public static GateResult pass() {
            return new GateResult(false, null);
        }
    }
}
