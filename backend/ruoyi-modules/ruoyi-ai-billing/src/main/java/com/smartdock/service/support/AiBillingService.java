package com.smartdock.service.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.DcsAiModelPrice;
import com.smartdock.domain.DcsAiUsage;
import com.smartdock.domain.bo.DcsAiUsageQueryBo;
import com.smartdock.domain.vo.DcsAiUsageVo;
import com.smartdock.mapper.DcsAiCreditLedgerMapper;
import com.smartdock.mapper.DcsAiUsageMapper;
import com.smartdock.service.IDcsAiModelPriceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 调用计费服务: 按调用次数 × 固定单价记录每次 AI 调用的消耗。
 *
 * <p>单价取自 {@link DcsAiConfig#getUnitPricePerCall()}, 默认 0.01 元。
 * MQ 线程无登录上下文时 tenantId 从 config 取, operatorId 为 null。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiBillingService {

    /** AI 用量流水 Mapper(记录每次调用消耗) */
    private final DcsAiUsageMapper usageMapper;
    /** 模型价格服务(取生效计费单价) */
    private final IDcsAiModelPriceService modelPriceService;
    /** 租户钱包服务(扣减 AI 消费额度) */
    private final TenantWalletService tenantWalletService;
    /** 积分流水 Mapper(R5 积分模式重放短路判定: 钱包流水不含该键) */
    private final DcsAiCreditLedgerMapper creditLedgerMapper;
    /** 积分服务(六审回修 dspro P0-1 方案A: 积分扣减并入 usage 同事务, 杜绝"单据在流水无"的静默免单) */
    private final com.smartdock.service.IDcsAiCreditService creditService;
    /** 计费计算器(内部构造,按 token/单价算金额) */
    private final AiBillingCalculator billingCalculator = new AiBillingCalculator();

    /**
     * 记录一次 AI 调用消耗。
     *
     * @param config    AI 配置(含单价)
     * @param caller    调用方标识(PROFILE_DIAGNOSE/FIELD_GENERATE/...)
     * @param scene     场景(可为 null)
     * @param latencyMs 耗时毫秒
     * @param success   是否成功
     * @param errorMsg  失败信息(成功为 null)
     */
    public void record(DcsAiConfig config, String caller, String scene, long latencyMs,
                       boolean success, String errorMsg) {
        record(config, caller, scene, null, latencyMs, success, errorMsg);
    }

    /**
     * 记录一次 AI 调用消耗。
     *
     * @param usageSnapshot Spring AI usage 元数据快照, 取不到时自动回退固定单价
     */
    public void record(DcsAiConfig config, String caller, String scene, AiTokenUsageSnapshot usageSnapshot, long latencyMs,
                       boolean success, String errorMsg) {
        try {
            DcsAiModelPrice price = modelPriceService.findEffectivePrice(config.getTenantId(), config.getProvider(), config.getModelName());
            AiBillingCalculator.Result billing = billingCalculator.calculate(config, price, usageSnapshot, success);
            DcsAiUsage usage = new DcsAiUsage();
            // 预生成雪花主键: 供扣费幂等键与流水 bizId 绑定本次单据(q5 拍板A, 2026-09-24; 全局 ASSIGN_ID 下预置值不会被覆盖)
            usage.setUsageId(IdWorker.getId());
            usage.setTenantId(config.getTenantId());
            usage.setConfigId(config.getConfigId());
            usage.setProvider(config.getProvider());
            usage.setModelName(config.getModelName());
            usage.setCaller(caller);
            usage.setScene(scene);
            usage.setOperatorId(safeOperatorId());
            usage.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
            usage.setUnitPrice(billing.unitPrice());
            usage.setBaseCostAmount(billing.baseCostAmount());
            usage.setServiceFeeRate(billing.serviceFeeRate());
            usage.setServiceFeeAmount(billing.serviceFeeAmount());
            usage.setCostAmount(billing.costAmount());
            usage.setCurrency(billing.currency());
            usage.setBillingMode(billing.billingMode());
            usage.setPricingSnapshot(billing.pricingSnapshot());
            if (usageSnapshot != null) {
                usage.setPromptTokens(usageSnapshot.promptTokens());
                usage.setCachedPromptTokens(usageSnapshot.cachedPromptTokens());
                usage.setCompletionTokens(usageSnapshot.completionTokens());
                usage.setTotalTokens(usageSnapshot.totalTokens());
            }
            usage.setSuccess(success ? "1" : "0");
            if (StrUtil.isNotBlank(errorMsg)) {
                usage.setErrorMsg(StrUtil.maxLength(errorMsg, 480));
            }
            // 成功调用才扣钱包; 失败不扣
            if (success && billing.costAmount() != null && billing.costAmount().compareTo(BigDecimal.ZERO) > 0) {
                try {
                    // 幂等键绑定本次用量单据: 原 System.currentTimeMillis() 同毫秒并发会撞 ledger 唯一索引(q5 拍板A);
                    // usageId 为本次调用预生成雪花号——同一次调用内的重复扣减可幂等, 业务层重试=新调用新单据(glm53 P2 口径收窄, 2026-09-24)
                    String idempotencyKey = "ai-usage:" + config.getTenantId() + ":" + config.getConfigId() + ":" + usage.getUsageId();
                    Long walletLedgerId = tenantWalletService.deductForAiUsage(
                        config.getTenantId(), billing.costAmount(), mapCallerToServiceCode(caller),
                        usage.getUsageId(), idempotencyKey);
                    if (walletLedgerId != null) {
                        usage.setWalletLedgerId(walletLedgerId);
                    }
                } catch (Exception we) {
                    // 扣费失败不阻塞记录, 只记日志(usage 仍写, wallet_deducted 为空)
                    log.warn("[AiBilling] 钱包扣费失败 config={} caller={}: {}", config.getConfigId(), caller, we.getMessage());
                }
            }
            usageMapper.insert(usage);
        } catch (Exception e) {
            // 计费失败不影响主流程
            log.warn("[AiBilling] 记录消耗失败 config={} caller={}: {}", config.getConfigId(), caller, e.getMessage());
        }
    }

    /**
     * 记录一次 AI 调用消耗(事务化+幂等键参数化)——批D D1-② 助手会话计费专属路径(S-07 红条兑现)。
     *
     * <p>与 {@link #record} 的差异(有意设计): ① 本方法<b>不吞异常</b>, 由调用方(计费装饰器)
     * 决定计费失败是否影响会话; ② usage 插入与钱包扣减 {@code deductForAiUsageJoinTx}
     * 同一事务同生共死, 杜绝"扣了费没单据/有单据没扣费"的悬挂分离; ③ 幂等键由调用方生成
     * (装饰器按会话+服务端序号), 重放同键返回原流水不重复扣; ④ 计费模式尊重配置行——
     * FIXED_PER_CALL 按次单价扣(批G G3 1008 拍板: 与估算/角标/免费豁免三处口径统一镜像,
     * 撤销批D"助手强制 OFFICIAL_TOKEN"旧口径——管理员设按次 0 元=免费意图, 强转 token
     * 计费在有价行时按真钱无封顶扣=资损); ⑤ 非本币(USD)计费只记 usage 不扣钱包
     * (钱包是 CNY 单币种, 直接扣等于把美元当人民币花)。</p>
     *
     * <p>余额不足(条件扣减 affected=0)时: usage 照记 + pricing_snapshot 打
     * walletDeducted:false 标记(对账 job 识别"有意免扣"不误报), 本笔不扣不拦——
     * 拦截发生在调用前置余额闸(装饰器), 事后不足只留痕。</p>
     *
     * @param operatorId     操作人(会话登录用户; 反应式线程无登录上下文, 由装饰器显式传入)
     * @param idempotencyKey 幂等键(调用方生成, 同键重放不重复扣)
     * @return usage 单据 ID(事务提交后供对账)
     */
    @Transactional(rollbackFor = Exception.class)
    public Long recordTransactional(DcsAiConfig config, String caller, String scene, AiTokenUsageSnapshot usageSnapshot,
                                    long latencyMs, boolean success, String errorMsg,
                                    Long operatorId, String idempotencyKey) {
        return doRecordTransactional(config, caller, scene, usageSnapshot, latencyMs, success, errorMsg,
            operatorId, idempotencyKey, true);
    }

    /**
     * 记录一次 AI 调用消耗——积分模式重载（五需求批 R5，2026-10-13）：订阅积分的租户助手对话
     * 走积分池，本方法只记 usage 单据<b>不动钱包</b>，pricing_snapshot 打
     * {@code walletDeducted:false/credits} 标记（对账 job 与 USD 分支区分）。
     *
     * <p><b>六审回修（dspro P0-1/k3 P1-2，方案A）</b>：creditsToCharge 非空时积分扣减在
     * <b>本方法同一事务内</b>执行（usage 插入 + 积分流水同生共死，与钱包路径的设计不变式
     * 对齐）——此前装饰器在事务外另起自动提交调 deduct，进程中断/订阅中途失效会产生
     * 「单据在、流水无」的静默免单且对账 job 对积分侧零覆盖。金额口径由调用方（计费装饰器，
     * priceOf 镜像）算好传入；null=非积分模式照旧。</p>
     *
     * <p>重放短路差异：钱包路径靠钱包流水的幂等键回查原 usageId；积分路径钱包流水恒不含该键，
     * 改查积分流水——同键已落过账返回 {@code null}（调用方跳过，不重复记 usage）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Long recordTransactionalCredits(DcsAiConfig config, String caller, String scene, AiTokenUsageSnapshot usageSnapshot,
                                           long latencyMs, boolean success, String errorMsg,
                                           Long operatorId, String idempotencyKey, Long creditsToCharge) {
        Long usageId = doRecordTransactional(config, caller, scene, usageSnapshot, latencyMs, success, errorMsg,
            operatorId, idempotencyKey, false);
        if (creditsToCharge != null && usageId != null) {
            // 同事务扣积分: deduct 内部已按订阅态/余量夹逼, 失效留 WARN、缺口进 remark;
            // operatorId=会话用户(dspro P1-1: DEDUCT 行 create_by 数据源, 双视角流水三件套)
            creditService.deduct(config.getTenantId(), creditsToCharge, scene, idempotencyKey + ":credit", operatorId);
        }
        return usageId;
    }

    private Long doRecordTransactional(DcsAiConfig config, String caller, String scene, AiTokenUsageSnapshot usageSnapshot,
                                       long latencyMs, boolean success, String errorMsg,
                                       Long operatorId, String idempotencyKey, boolean chargeWallet) {
        // 重放短路: 同键已落过账(usage+ledger 同事务成功)则直接返回原 usageId——
        // 否则顺序重放会再插一条 usage 挂同一 ledger, 对账④金额不平(2026-10-05 设计期发现)
        Long replayed = tenantWalletService.deductedBizIdByIdempotency(idempotencyKey);
        if (replayed == null && !chargeWallet) {
            // 积分模式: 钱包流水不含该键属常态, 改查积分流水(装饰器落账键=同键+":credit")
            if (creditLedgerMapper.existsByIdempotencyKey(idempotencyKey + ":credit") > 0) {
                log.info("[AiBilling] 积分模式幂等键重放短路: key={}", idempotencyKey);
                return null;
            }
        }
        if (replayed != null) {
            log.info("[AiBilling] 幂等键重放短路, 沿用原单据: key={} usageId={}", idempotencyKey, replayed);
            return replayed;
        }
        DcsAiModelPrice price = modelPriceService.findEffectivePrice(config.getTenantId(), config.getProvider(), config.getModelName());
        // G3(批G 1008)：不再强转 OFFICIAL_TOKEN——calculator 按 config.billingMode 原样分派，
        // FIXED_PER_CALL 走 fixed 分支=CNY 次价封顶，与装饰器估算/角标/免费档三处镜像
        AiBillingCalculator.Result billing = billingCalculator.calculate(config, price, usageSnapshot, success);
        DcsAiUsage usage = new DcsAiUsage();
        usage.setUsageId(IdWorker.getId());
        usage.setTenantId(config.getTenantId());
        usage.setConfigId(config.getConfigId());
        usage.setProvider(config.getProvider());
        usage.setModelName(config.getModelName());
        usage.setCaller(caller);
        usage.setScene(scene);
        usage.setOperatorId(operatorId);
        usage.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
        usage.setUnitPrice(billing.unitPrice());
        usage.setBaseCostAmount(billing.baseCostAmount());
        usage.setServiceFeeRate(billing.serviceFeeRate());
        usage.setServiceFeeAmount(billing.serviceFeeAmount());
        usage.setCostAmount(billing.costAmount());
        usage.setCurrency(billing.currency());
        usage.setBillingMode(billing.billingMode());
        usage.setPricingSnapshot(billing.pricingSnapshot());
        if (usageSnapshot != null) {
            usage.setPromptTokens(usageSnapshot.promptTokens());
            usage.setCachedPromptTokens(usageSnapshot.cachedPromptTokens());
            usage.setCompletionTokens(usageSnapshot.completionTokens());
            usage.setTotalTokens(usageSnapshot.totalTokens());
        }
        usage.setSuccess(success ? "1" : "0");
        if (StrUtil.isNotBlank(errorMsg)) {
            usage.setErrorMsg(StrUtil.maxLength(errorMsg, 480));
        }
        String snapshot = billing.pricingSnapshot();
        if (success && billing.costAmount() != null && billing.costAmount().compareTo(BigDecimal.ZERO) > 0) {
            if (!chargeWallet) {
                // R5 积分模式: 只记单据不动钱包(扣的是积分池), 标记口径与 USD 分支区分
                snapshot = mergeSnapshotFlag(snapshot, "walletDeducted", false, "credits");
            } else if (!"CNY".equalsIgnoreCase(billing.currency())) {
                // 钱包单币种 CNY: 美元价直接扣=把美元当人民币花, 只记单据打免扣标记, 人工汇率结算
                log.warn("[AiBilling] 非本币计费不入钱包(仅记 usage): tenant={} model={} currency={} cost={}",
                    config.getTenantId(), config.getModelName(), billing.currency(), billing.costAmount());
                snapshot = mergeSnapshotFlag(snapshot, "walletDeducted", false, "currency:" + billing.currency());
            } else {
                try {
                    // 坑68 同精度: 计算在 scale-8, 入钱包/流水前四舍五入一次到 scale-4,
                    // 之后 wallet 扣减与 ledger 金额共用这一个值——两侧各自舍入会差尾数断链
                    BigDecimal walletAmount = billing.costAmount().setScale(4, RoundingMode.HALF_UP);
                    Long walletLedgerId = tenantWalletService.deductForAiUsageJoinTx(
                        config.getTenantId(), walletAmount, mapCallerToServiceCode(caller),
                        usage.getUsageId(), idempotencyKey);
                    if (walletLedgerId != null) {
                        usage.setWalletLedgerId(walletLedgerId);
                    }
                } catch (ServiceException insufficient) {
                    // 余额不足: usage 照记(消费事实), 打免扣标记(对账不误报), 前置闸下轮拦
                    log.warn("[AiBilling] 助手扣费余额不足 tenant={}: {}", config.getTenantId(), insufficient.getMessage());
                    snapshot = mergeSnapshotFlag(snapshot, "walletDeducted", false, "insufficient");
                }
            }
        }
        usage.setPricingSnapshot(snapshot);
        usageMapper.insert(usage);
        return usage.getUsageId();
    }

    /**
     * 在计费快照 JSON 顶层并入一个布尔标记(快照为空/非对象/空对象 "{}" 时重建, glm53 终审 P1:
     * 拼接分支必须在 flag 与原首键之间补逗号, 且 "{}" 重建分支不残留尾逗号)。
     */
    private String mergeSnapshotFlag(String snapshot, String key, boolean value, String reason) {
        String flag = "\"" + key + "\":" + value + ",\"reason\":\"" + reason + "\"";
        if (snapshot == null || snapshot.length() < 2 || snapshot.charAt(0) != '{') {
            return "{" + flag + "}";
        }
        String rest = snapshot.substring(1).trim();
        if (rest.isEmpty() || rest.equals("}")) {
            return "{" + flag + "}";
        }
        return "{" + flag + "," + rest;
    }

    /**
     * 查询当前租户 AI 消耗台账(分页)。
     */
    public TableDataInfo<DcsAiUsageVo> usagePage(DcsAiUsageQueryBo bo, PageQuery pageQuery) {
        String tenantId = LoginHelper.getTenantId();
        LambdaQueryWrapper<DcsAiUsage> lqw = usageWrapper(bo, tenantId);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<DcsAiUsageVo> page = usageMapper.selectVoPage(pageQuery.build(), lqw);
        return TableDataInfo.build(page);
    }

    /**
     * AI 缺口批 Q7（2026-10-14）：按当前筛选导出全量明细（与 usagePage 同构，无分页；
     * 上限 1 万行防呆——超限导出前 1 万行，管理台对账场景足够）。
     */
    public java.util.List<DcsAiUsage> usageExportList(DcsAiUsageQueryBo bo) {
        return usageMapper.selectList(usageWrapper(bo, LoginHelper.getTenantId()).last("LIMIT 10000"));
    }

    private LambdaQueryWrapper<DcsAiUsage> usageWrapper(DcsAiUsageQueryBo bo, String tenantId) {
        LambdaQueryWrapper<DcsAiUsage> lqw = new LambdaQueryWrapper<DcsAiUsage>()
            .eq(DcsAiUsage::getTenantId, tenantId)
            .orderByDesc(DcsAiUsage::getCreateTime);
        if (bo != null) {
            if (StrUtil.isNotBlank(bo.getCaller())) lqw.eq(DcsAiUsage::getCaller, bo.getCaller());
            if (StrUtil.isNotBlank(bo.getScene())) lqw.eq(DcsAiUsage::getScene, bo.getScene());
            if (bo.getConfigId() != null) lqw.eq(DcsAiUsage::getConfigId, bo.getConfigId());
        }
        return lqw;
    }

    /**
     * 当前租户 AI 消耗统计(总量/总金额/成功率/各调用方分布)。
     */
    public Map<String, Object> usageSummary() {
        String tenantId = LoginHelper.getTenantId();
        java.util.List<DcsAiUsage> all = usageMapper.selectList(
            new LambdaQueryWrapper<DcsAiUsage>().eq(DcsAiUsage::getTenantId, tenantId));
        long totalCalls = all.size();
        long successCalls = all.stream().filter(u -> "1".equals(u.getSuccess())).count();
        BigDecimal totalCost = all.stream()
            .map(DcsAiUsage::getCostAmount)
            .filter(java.util.Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(4, RoundingMode.HALF_UP);
        // 按调用方分组
        Map<String, Long> byCaller = new LinkedHashMap<>();
        Map<String, BigDecimal> costByCaller = new LinkedHashMap<>();
        for (DcsAiUsage u : all) {
            String key = StrUtil.blankToDefault(u.getCaller(), "UNKNOWN");
            byCaller.merge(key, 1L, Long::sum);
            BigDecimal c = u.getCostAmount() == null ? BigDecimal.ZERO : u.getCostAmount();
            costByCaller.merge(key, c, BigDecimal::add);
        }
        // 最近 7 天每日消耗
        Map<String, BigDecimal> dailyCost = new LinkedHashMap<>();
        Date now = new Date();
        long sevenDaysAgo = now.getTime() - 7L * 24 * 3600 * 1000;
        for (DcsAiUsage u : all) {
            if (u.getCreateTime() != null && u.getCreateTime().getTime() >= sevenDaysAgo) {
                String day = u.getCreateTime().toInstant().toString().substring(0, 10);
                BigDecimal c = u.getCostAmount() == null ? BigDecimal.ZERO : u.getCostAmount();
                dailyCost.merge(day, c, BigDecimal::add);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCalls", totalCalls);
        result.put("successCalls", successCalls);
        result.put("totalCost", totalCost);
        result.put("successRate", totalCalls == 0 ? 0 : Math.round((double) successCalls / totalCalls * 10000) / 100.0);
        result.put("byCaller", byCaller);
        result.put("costByCaller", costByCaller);
        result.put("dailyCost", dailyCost);
        return result;
    }

    /**
     * 业务批7 Q7：今日 AI 助手缓存命中率（LiteLLM 口径 cached/prompt）。只算真实 usage
     * 行——排除 |est 估算行（cached 恒 0 会把命中率打成假 0，New API 同坑实证）与失败行。
     * 样本 &lt; 3 时 rate=null（前端显示占位，不瞎算）。
     * <p>Q1 护栏（2026-10-15 拍板A）：单笔 prompt &gt; 100 万 token 的行不进统计——真实对话
     * 单笔最多 2~3 万，超限行只可能是测试种子/哨兵值（1008 实锤：d1:poor 测试行单笔 1 亿
     * token、cached 恒 0，两笔混进 assist: 前缀即把命中打到 0.3%）。存量脏行不删，
     * 本条件天然排除。</p>
     */
    public Map<String, Object> assistantCacheHitToday(String tenantId, Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rate", null);
        out.put("samples", 0);
        try {
            Date dayStart = cn.hutool.core.date.DateUtil.beginOfDay(new Date());
            List<Map<String, Object>> rows = usageMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DcsAiUsage>()
                    .select("COALESCE(SUM(cached_prompt_tokens),0) AS cached",
                        "COALESCE(SUM(prompt_tokens),0) AS prompt",
                        "COUNT(*) AS samples")
                    .eq("tenant_id", tenantId)
                    .eq("operator_id", userId)
                    .eq("success", "1")
                    .likeRight("scene", "assist:")
                    .notLike("scene", "%|est")
                    // dspro P2-6：排除侧车调用（|sidecar:followup/title）——状态卡口径=
                    // 用户主链对话命中率，系统内部代问不计入（侧车 token 结构与主链不同，混入失真）
                    .notLike("scene", "%|sidecar%")
                    // Q1 护栏：单笔超 100 万 token=测试种子/哨兵值，不进命中率统计
                    .le("prompt_tokens", 1_000_000)
                    .ge("create_time", dayStart));
            if (rows == null || rows.isEmpty()) {
                return out;
            }
            long cached = rows.get(0).get("cached") instanceof Number n0 ? n0.longValue() : 0L;
            long prompt = rows.get(0).get("prompt") instanceof Number n1 ? n1.longValue() : 0L;
            long samples = rows.get(0).get("samples") instanceof Number n2 ? n2.longValue() : 0L;
            out.put("samples", samples);
            if (prompt > 0 && samples >= 3) {
                out.put("rate", Math.round(cached * 100.0 / prompt));
            }
        } catch (Exception e) {
            log.warn("[AiBilling] 缓存命中率聚合失败(降级空值) tenant={} user={}", tenantId, userId, e);
        }
        return out;
    }

    private Long safeOperatorId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将 AI 调用方标签映射为服务计费规则 service_code。
     */
    private String mapCallerToServiceCode(String caller) {
        if (StrUtil.isBlank(caller)) {
            return "AI_PROFILE_SCORE";
        }
        return switch (caller) {
            case "PROFILE_DIAGNOSE" -> "AI_PROFILE_DIAGNOSE";
            case "FIELD_GENERATE" -> "AI_FIELD_GENERATE";
            case "SCORE_ENHANCE" -> "AI_SCORE_ENHANCE";
            case "SCORE_RULE_RECOMMEND" -> "AI_SCORE_RULE_RECOMMEND";
            case "ASSISTANT_CHAT" -> "AI_ASSISTANT_CHAT";
            default -> "AI_PROFILE_SCORE";
        };
    }
}
