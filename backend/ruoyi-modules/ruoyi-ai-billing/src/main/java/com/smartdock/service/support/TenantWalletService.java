package com.smartdock.service.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.domain.DcsServiceChargeRule;
import com.smartdock.domain.DcsTenantWallet;
import com.smartdock.domain.DcsTenantWalletLedger;
import com.smartdock.domain.DcsWalletAlertState;
import com.smartdock.domain.bo.WalletOperationBo;
import com.smartdock.domain.vo.DcsServiceChargeRuleVo;
import com.smartdock.domain.vo.DcsTenantWalletLedgerVo;
import com.smartdock.domain.vo.DcsTenantWalletVo;
import com.smartdock.domain.vo.FallbackDecision;
import com.smartdock.mapper.DcsServiceChargeRuleMapper;
import com.smartdock.mapper.DcsTenantWalletLedgerMapper;
import com.smartdock.mapper.DcsTenantWalletMapper;
import com.smartdock.mapper.DcsWalletAlertStateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 租户钱包核心服务: 充值/扣减/冻结/兜底决策/低余额检测。
 *
 * <p>所有钱包写操作必须在 {@code @Transactional} 内, 使用乐观锁 version 字段防并发。
 * 扣减幂等键防止 AI 重复扣费或支付回调重复充值。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantWalletService {

    private static final String GLOBAL_TENANT_ID = "000000";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_FROZEN = "FROZEN";
    private static final String DIRECTION_IN = "IN";
    private static final String DIRECTION_OUT = "OUT";
    private static final BigDecimal GIFT_AMOUNT = new BigDecimal("10.0000");

    /** 租户钱包账户 Mapper(余额/冻结) */
    private final DcsTenantWalletMapper walletMapper;
    /** 钱包流水账 Mapper(充值/扣费/冻结记录) */
    private final DcsTenantWalletLedgerMapper ledgerMapper;
    /** 服务计费规则 Mapper(按类型匹配扣费策略) */
    private final DcsServiceChargeRuleMapper ruleMapper;
    /** 钱包告警状态 Mapper(低余额/告警去重) */
    private final DcsWalletAlertStateMapper alertStateMapper;

    // ========== 查询 ==========

    /**
     * 分页查询租户钱包(可选按租户过滤), 自动填充可用余额。
     */
    public TableDataInfo<DcsTenantWalletVo> queryPageList(PageQuery pageQuery, String tenantId) {
        // 深扫回修(2026-10-16 P0-2): 本表 Mapper 关闭租户拦截器, 非超管强制收窄本租户——
        // 持 dcs:wallet:list 的租户侧角色不传 tenantId 也不能拉全平台钱包(与 walletSummary 同口径)
        String scopeTenantId = scopeTenantId(tenantId);
        LambdaQueryWrapper<DcsTenantWallet> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(scopeTenantId)) {
            wrapper.eq(DcsTenantWallet::getTenantId, scopeTenantId);
        } else if (!LoginHelper.isSuperAdmin()) {
            return TableDataInfo.build(new Page<>());
        }
        wrapper.orderByDesc(DcsTenantWallet::getCreateTime);
        Page<DcsTenantWalletVo> page = walletMapper.selectVoPage(pageQuery.build(), wrapper);
        page.getRecords().forEach(this::fillAvailable);
        return TableDataInfo.build(page);
    }

    /**
     * 钱包全量汇总(账户与账单页 KPI): 钱包数/总余额/总冻结/低于阈值数/异常数。
     * 钱包行数为租户级规模, 全量拉取内存聚合足够; 替代旧页"当前页余额"口径。
     * 深审回修(2026-10-16 P1-3): 非超管强制收窄到自己租户(本表 Mapper 关闭了租户拦截器,
     * 不收窄则未来任何授权角色一次拿到全平台资金总量); P2-4: 低于阈值口径统一 th>0(与前端高亮行一致)。
     * 深扫回修(P1-1): 超管传 null=全平台口径(KPI 与下方钱包表格一致, 控制器不再传登录租户)。
     */
    public Map<String, Object> walletSummary(String tenantId) {
        String scopeTenantId = scopeTenantId(tenantId);
        LambdaQueryWrapper<DcsTenantWallet> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(scopeTenantId)) {
            wrapper.eq(DcsTenantWallet::getTenantId, scopeTenantId);
        } else if (!LoginHelper.isSuperAdmin()) {
            // 深扫回修(P1-3 fail-closed): 非超管且登录上下文取不到租户ID(异常路径)时,
            // 禁止退化为全平台聚合——返回空汇总而非放大权限
            Map<String, Object> empty = new HashMap<>();
            empty.put("walletCount", 0);
            empty.put("totalBalance", BigDecimal.ZERO);
            empty.put("totalFrozen", BigDecimal.ZERO);
            empty.put("belowThreshold", 0L);
            empty.put("abnormal", 0L);
            return empty;
        }
        List<DcsTenantWallet> all = walletMapper.selectList(wrapper);
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal frozen = BigDecimal.ZERO;
        long belowThreshold = 0;
        long abnormal = 0;
        for (DcsTenantWallet w : all) {
            BigDecimal bal = w.getBalanceAmount() == null ? BigDecimal.ZERO : w.getBalanceAmount();
            BigDecimal frz = w.getFrozenAmount() == null ? BigDecimal.ZERO : w.getFrozenAmount();
            balance = balance.add(bal);
            frozen = frozen.add(frz);
            BigDecimal th = w.getLowBalanceThreshold();
            if (th != null && th.compareTo(BigDecimal.ZERO) > 0 && bal.compareTo(th) < 0) {
                belowThreshold++;
            }
            if (!STATUS_ACTIVE.equals(w.getStatus())) {
                abnormal++;
            }
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("walletCount", all.size());
        summary.put("totalBalance", balance);
        summary.put("totalFrozen", frozen);
        summary.put("belowThreshold", belowThreshold);
        summary.put("abnormal", abnormal);
        return summary;
    }

    /**
     * 按主键查询钱包, 不存在抛 ServiceException, 并填充可用余额。
     */
    public DcsTenantWalletVo queryById(Long walletId) {
        DcsTenantWalletVo vo = walletMapper.selectVoById(walletId);
        if (vo == null) {
            throw new ServiceException("钱包不存在");
        }
        // 深扫回修(P0-2): 按 walletId 直读的归属校验(非超管只能读本租户钱包)
        assertTenantScope(vo.getTenantId());
        fillAvailable(vo);
        return vo;
    }

    /**
     * 计算并回填可用余额(余额 - 冻结), 保留 4 位小数。
     */
    private void fillAvailable(DcsTenantWalletVo vo) {
        if (vo == null || vo.getBalanceAmount() == null) {
            return;
        }
        BigDecimal frozen = vo.getFrozenAmount() == null ? BigDecimal.ZERO : vo.getFrozenAmount();
        vo.setAvailableAmount(vo.getBalanceAmount().subtract(frozen).setScale(4, RoundingMode.HALF_UP));
    }

    // ========== 初始化 ==========

    /**
     * 获取或创建租户主钱包(幂等)。
     */
    @Transactional(rollbackFor = Exception.class)
    public DcsTenantWallet getOrCreateMainWallet(String tenantId) {
        String resolved = StrUtil.blankToDefault(tenantId, GLOBAL_TENANT_ID);
        DcsTenantWallet wallet = findMainWallet(resolved);
        if (wallet != null) {
            return wallet;
        }
        wallet = new DcsTenantWallet();
        wallet.setTenantId(resolved);
        wallet.setWalletType("MAIN");
        wallet.setBalanceAmount(BigDecimal.ZERO);
        wallet.setFrozenAmount(BigDecimal.ZERO);
        wallet.setCurrency("CNY");
        wallet.setStatus(STATUS_ACTIVE);
        wallet.setLowBalanceThreshold(new BigDecimal("1.00"));
        wallet.setNegativeAllowed("0");
        wallet.setRemark("自动初始化主钱包");
        wallet.setVersion(0);
        // 并发首建撞唯一索引时异常直接穿出(败者本回合计费失败=ERROR 日志可见, 下回合重查即成功)——
        // 不能 catch 重查: PG 语句失败后事务已 abort, 同事务内继续执行任何语句都会 25P02
        walletMapper.insert(wallet);
        // 赠送体验金
        doRecharge(wallet, GIFT_AMOUNT, "ADMIN_GIFT", "系统初始化赠送体验金",
            "init:" + resolved, safeOperatorId());
        return wallet;
    }

    /**
     * 按租户查询主钱包(不存在返回 null)。
     */
    private DcsTenantWallet findMainWallet(String tenantId) {
        return walletMapper.selectOne(new LambdaQueryWrapper<DcsTenantWallet>()
            .eq(DcsTenantWallet::getTenantId, tenantId)
            .eq(DcsTenantWallet::getWalletType, "MAIN")
            .eq(DcsTenantWallet::getDelFlag, "0"));
    }

    /**
     * 为指定租户初始化主钱包(幂等, 供 Controller 调用)。
     */
    @Transactional(rollbackFor = Exception.class)
    public DcsTenantWalletVo initMainWallet(String tenantId) {
        DcsTenantWallet wallet = getOrCreateMainWallet(tenantId);
        DcsTenantWalletVo vo = MapstructUtils.convert(wallet, DcsTenantWalletVo.class);
        fillAvailable(vo);
        return vo;
    }

    // ========== 手动操作 ==========

    /**
     * 管理员手动充值(幂等键缺省时按钱包+时间戳生成)。
     */
    @Transactional(rollbackFor = Exception.class)
    public DcsTenantWalletVo manualRecharge(Long walletId, WalletOperationBo bo) {
        DcsTenantWallet wallet = loadAndCheck(walletId);
        String idempotencyKey = StrUtil.blankToDefault(bo.getIdempotencyKey(),
            "recharge:" + walletId + ":" + System.currentTimeMillis());
        doRecharge(wallet, bo.getAmount(), "RECHARGE", bo.getReason(), idempotencyKey, safeOperatorId());
        return queryById(walletId);
    }

    /**
     * 管理员手动扣减(ADJUST 类型, 幂等键缺省时按钱包+时间戳生成)。
     */
    @Transactional(rollbackFor = Exception.class)
    public DcsTenantWalletVo manualDeduct(Long walletId, WalletOperationBo bo) {
        DcsTenantWallet wallet = loadAndCheck(walletId);
        String idempotencyKey = StrUtil.blankToDefault(bo.getIdempotencyKey(),
            "deduct:" + walletId + ":" + System.currentTimeMillis());
        doDeduct(wallet, bo.getAmount(), "ADJUST", null, null, bo.getReason(), idempotencyKey, safeOperatorId());
        return queryById(walletId);
    }

    /**
     * 更新钱包低余额阈值。
     */
    @Transactional(rollbackFor = Exception.class)
    public DcsTenantWalletVo updateThreshold(Long walletId, BigDecimal threshold) {
        loadAndCheck(walletId); // 深扫回修(P0-2): 归属校验(非超管拒绝跨租户)
        walletMapper.update(null, new LambdaUpdateWrapper<DcsTenantWallet>()
            .eq(DcsTenantWallet::getWalletId, walletId)
            .set(DcsTenantWallet::getLowBalanceThreshold, threshold));
        return queryById(walletId);
    }

    /**
     * 更新钱包状态(ACTIVE/FROZEN/DISABLED), 非法状态抛 ServiceException。
     */
    @Transactional(rollbackFor = Exception.class)
    public DcsTenantWalletVo updateStatus(Long walletId, String status) {
        if (!STATUS_ACTIVE.equals(status) && !STATUS_FROZEN.equals(status) && !"DISABLED".equals(status)) {
            throw new ServiceException("非法钱包状态: " + status);
        }
        loadAndCheck(walletId); // 深扫回修(P0-2): 归属校验(非超管拒绝跨租户)
        walletMapper.update(null, new LambdaUpdateWrapper<DcsTenantWallet>()
            .eq(DcsTenantWallet::getWalletId, walletId)
            .set(DcsTenantWallet::getStatus, status));
        return queryById(walletId);
    }

    // ========== AI 扣减 ==========

    /**
     * AI 调用扣减钱包(幂等, 乐观锁)。
     *
     * @param tenantId      租户
     * @param amount        扣减金额(已含服务费)
     * @param serviceCode   服务码(AI_PROFILE_SCORE 等)
     * @param bizId         关联 usage_id
     * @param idempotencyKey 幂等键(防重复扣)
     * @return ledgerId(已存在则返回原流水ID)
     */
    // q5(2026-09-24 拍板A): REQUIRES_NEW 独立事务——外层(算分/AI增强)事务回滚不陪葬已完成的扣费;
    // 反之扣费异常被 AiBillingService catch 吞掉时, 也不会把外层事务标记 rollback-only 导致整批算分 UnexpectedRollbackException。
    // 口径说明: 外层回滚时 usage 单据随之外滚消失、扣费已独立提交——悬挂扣费以 idempotency_key=
    // "ai-usage:{tenantId}:{configId}:{usageId}" 反查(见计划 G-7 对账 job, 应尽快立项兜底)
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Long deductForAiUsage(String tenantId, BigDecimal amount, String serviceCode,
                                 Long bizId, String idempotencyKey) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        DcsTenantWallet wallet = getOrCreateMainWallet(tenantId);
        // 幂等: 已存在同 key 流水则不重复扣
        DcsTenantWalletLedger existing = findLedgerByIdempotency(idempotencyKey);
        if (existing != null) {
            return existing.getLedgerId();
        }
        doDeduct(wallet, amount, "DEDUCT", bizId, serviceCode, "AI调用扣费", idempotencyKey, safeOperatorId());
        DcsTenantWalletLedger ledger = findLedgerByIdempotency(idempotencyKey);
        return ledger == null ? null : ledger.getLedgerId();
    }

    /**
     * AI 调用扣减钱包(幂等, 条件更新原子扣减, 加入调用方事务)——批D D1-③ 专属路径。
     *
     * <p>与 {@link #deductForAiUsage} 的三点差异(有意设计, 勿合并):</p>
     * <ol>
     *   <li><b>传播=REQUIRED</b>: 调用方(AiBillingService.recordTransactional)同事务持有
     *       usage 插入与扣费, 两者同生共死——S-07 红条(usage 单与 ledger 悬挂分离)的正解;
     *       旧路径 REQUIRES_NEW 是 q5 拍板, 服务算分外层回滚不陪葬, 两条路径语义不同;</li>
     *   <li><b>条件更新替代乐观锁</b>: 单条 UPDATE 原子判定"足额才扣"(P16 配方移植,
     *       生产先例是乐观锁, 本路径以并发用例验证移植正确性), 余额不足抛 ServiceException;</li>
     *   <li><b>流水期初由扣减后余额反推</b>(after + amount): 相对减串行于行锁,
     *       即使本事务等待前一笔扣减提交, 链式流水(期初=前笔期末)依然连续。</li>
     * </ol>
     *
     * <p>noRollbackFor(ServiceException): 余额不足(条件更新 affected=0, 无任何写入)从本代理
     * 抛出时不得把外层共享事务标记 rollback-only——外层 recordTransactional 会捕获该异常
     * 留痕免扣标记后继续提交 usage 单据(否则 UnexpectedRollbackException, 单测实证)。</p>
     */
    @Transactional(rollbackFor = Exception.class, noRollbackFor = ServiceException.class)
    public Long deductForAiUsageJoinTx(String tenantId, BigDecimal amount, String serviceCode,
                                       Long bizId, String idempotencyKey) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        DcsTenantWallet wallet = getOrCreateMainWallet(tenantId);
        DcsTenantWalletLedger existing = findLedgerByIdempotency(idempotencyKey);
        if (existing != null) {
            // 迟到竞态防护(glm53 P2): 键已被他笔占用时不得把新 usage 挂上别人的流水
            // (对账④会金额不平误报); 助手键恒带本次 usageId, 命中即异常回滚
            if (bizId == null || !bizId.equals(existing.getBizId())) {
                throw new ServiceException("钱包扣费幂等键已被其它单据占用: " + idempotencyKey);
            }
            return existing.getLedgerId();
        }
        doDeductConditional(wallet, amount, bizId, serviceCode, idempotencyKey);
        DcsTenantWalletLedger ledger = findLedgerByIdempotency(idempotencyKey);
        return ledger == null ? null : ledger.getLedgerId();
    }

    /**
     * 按幂等键查询已扣费单据 ID(ledger.biz_id=usage_id), 未扣过返回 null——
     * 供事务化计费入口做重放短路(批D D1: 同键重放不重复插 usage, 防对账④误报)。
     */
    public Long deductedBizIdByIdempotency(String idempotencyKey) {
        if (StrUtil.isBlank(idempotencyKey)) {
            return null;
        }
        DcsTenantWalletLedger ledger = findLedgerByIdempotency(idempotencyKey);
        return ledger == null ? null : ledger.getBizId();
    }

    /**
     * 查询租户可用余额(余额-冻结, 无钱包按 0 计), 供助手调用前置余额提示(批D D1-⑤)。
     */
    public BigDecimal availableBalanceOf(String tenantId) {
        DcsTenantWallet wallet = findMainWallet(StrUtil.blankToDefault(tenantId, GLOBAL_TENANT_ID));
        if (wallet == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal frozen = wallet.getFrozenAmount() == null ? BigDecimal.ZERO : wallet.getFrozenAmount();
        return wallet.getBalanceAmount().subtract(frozen).setScale(4, RoundingMode.HALF_UP);
    }

    // ========== 兜底决策 ==========

    /**
     * 检查钱包余额是否足够, 不够时返回兜底策略。
     *
     * @param tenantId      租户
     * @param serviceCode   服务码
     * @param estimatedCost 预估成本(可为 null, null 时只看余额是否>0)
     * @return FallbackDecision
     */
    public FallbackDecision checkSufficientOrFallback(String tenantId, String serviceCode, BigDecimal estimatedCost) {
        DcsServiceChargeRule rule = findRule(tenantId, serviceCode);
        DcsTenantWallet wallet = findMainWallet(StrUtil.blankToDefault(tenantId, GLOBAL_TENANT_ID));

        // 无钱包: 按规则兜底
        if (wallet == null) {
            return decideByRule(rule, "钱包未初始化");
        }
        if (!STATUS_ACTIVE.equals(wallet.getStatus())) {
            return decideByRule(rule, "钱包已" + (STATUS_FROZEN.equals(wallet.getStatus()) ? "冻结" : "停用"));
        }
        BigDecimal available = wallet.getBalanceAmount().subtract(
            wallet.getFrozenAmount() == null ? BigDecimal.ZERO : wallet.getFrozenAmount());
        BigDecimal need = estimatedCost == null ? BigDecimal.ZERO : estimatedCost;
        if (available.compareTo(need) > 0 && available.compareTo(BigDecimal.ZERO) > 0) {
            return FallbackDecision.pass();
        }
        // 余额不足
        String msg = "AI额度不足, 当前可用余额 " + available.setScale(4, RoundingMode.HALF_UP) + " 元";
        return decideByRule(rule, msg);
    }

    /**
     * 按计费规则的 fallback 策略(算法降级/免费/管理员放行/默认阻断)生成兜底决策。
     */
    private FallbackDecision decideByRule(DcsServiceChargeRule rule, String reason) {
        if (rule == null) {
            return FallbackDecision.block(reason + ", 请充值");
        }
        String policy = StrUtil.blankToDefault(rule.getFallbackPolicy(), "BLOCK");
        return switch (policy) {
            case "ALGORITHM_FALLBACK" -> FallbackDecision.algorithmFallback(reason);
            case "ALLOW_FREE" -> FallbackDecision.allowFree();
            case "ADMIN_OVERRIDE" -> FallbackDecision.pass();
            default -> FallbackDecision.block(reason + ", 请充值");
        };
    }

    /**
     * 查询启用的计费规则(租户级优先于全局兜底)。
     */
    private DcsServiceChargeRule findRule(String tenantId, String serviceCode) {
        String resolved = StrUtil.blankToDefault(tenantId, GLOBAL_TENANT_ID);
        List<DcsServiceChargeRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<DcsServiceChargeRule>()
            .in(DcsServiceChargeRule::getTenantId, resolved, GLOBAL_TENANT_ID)
            .eq(DcsServiceChargeRule::getServiceCode, serviceCode)
            .eq(DcsServiceChargeRule::getEnabled, "1")
            .eq(DcsServiceChargeRule::getDelFlag, "0"));
        // 租户级优先于全局
        return rules.stream()
            .min((a, b) -> {
                int aScore = GLOBAL_TENANT_ID.equals(a.getTenantId()) ? 1 : 0;
                int bScore = GLOBAL_TENANT_ID.equals(b.getTenantId()) ? 1 : 0;
                return Integer.compare(aScore, bScore);
            })
            .orElse(null);
    }

    // ========== 计费规则 CRUD ==========

    /**
     * 分页查询计费规则(可选按租户/服务码过滤), 按 sortOrder 升序。
     */
    public TableDataInfo<DcsServiceChargeRuleVo> rulePageList(PageQuery pageQuery, String tenantId, String serviceCode) {
        LambdaQueryWrapper<DcsServiceChargeRule> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(tenantId)) {
            wrapper.eq(DcsServiceChargeRule::getTenantId, tenantId);
        }
        if (StrUtil.isNotBlank(serviceCode)) {
            wrapper.eq(DcsServiceChargeRule::getServiceCode, serviceCode);
        }
        wrapper.orderByAsc(DcsServiceChargeRule::getSortOrder);
        Page<DcsServiceChargeRuleVo> page = ruleMapper.selectVoPage(pageQuery.build(), wrapper);
        return TableDataInfo.build(page);
    }

    /**
     * 按主键查询计费规则, 不存在抛 ServiceException。
     */
    public DcsServiceChargeRuleVo ruleById(Long ruleId) {
        DcsServiceChargeRuleVo vo = ruleMapper.selectVoById(ruleId);
        if (vo == null) {
            throw new ServiceException("计费规则不存在");
        }
        return vo;
    }

    /**
     * 新增计费规则(规范化字段后入库), 返回主键。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long ruleInsert(DcsServiceChargeRule entity) {
        normalizeRule(entity);
        ruleMapper.insert(entity);
        return entity.getRuleId();
    }

    /**
     * 更新计费规则(规范化字段后更新)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean ruleUpdate(DcsServiceChargeRule entity) {
        normalizeRule(entity);
        return ruleMapper.updateById(entity) > 0;
    }

    /**
     * 批量删除计费规则。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean ruleDelete(List<Long> ruleIds) {
        return ruleMapper.deleteByIds(ruleIds) > 0;
    }

    /**
     * 规范化计费规则字段(补默认租户/服务码/扣费模式/兜底策略/启用标记)。
     */
    private void normalizeRule(DcsServiceChargeRule entity) {
        entity.setTenantId(StrUtil.blankToDefault(entity.getTenantId(), GLOBAL_TENANT_ID));
        entity.setServiceCode(StrUtil.trim(entity.getServiceCode()));
        entity.setWalletScope(StrUtil.blankToDefault(entity.getWalletScope(), "MAIN"));
        entity.setChargeMode(StrUtil.blankToDefault(entity.getChargeMode(), "AI_USAGE_COST"));
        entity.setFallbackPolicy(StrUtil.blankToDefault(entity.getFallbackPolicy(), "BLOCK"));
        entity.setEnabled(StrUtil.blankToDefault(entity.getEnabled(), "1"));
    }

    // ========== 内部方法 ==========

    /**
     * 执行充值: 更新余额并写入 IN 方向流水。
     */
    private void doRecharge(DcsTenantWallet wallet, BigDecimal amount, String serviceCode,
                            String reason, String idempotencyKey, Long operatorId) {
        BigDecimal before = wallet.getBalanceAmount();
        BigDecimal after = before.add(amount);
        updateBalance(wallet, after);
        insertLedger(wallet, "RECHARGE", null, serviceCode, DIRECTION_IN, amount,
            before, after, idempotencyKey, operatorId, reason);
    }

    /**
     * 执行扣减: 校验余额(允许负数时跳过)、更新余额并写入 OUT 方向流水。
     *
     * @param bizId 关联业务单据ID(AI 扣费=usage_id; 手工调账传 null) —— glm53 终审 P1(2026-09-24)接线
     */
    private void doDeduct(DcsTenantWallet wallet, BigDecimal amount, String bizType, Long bizId,
                          String serviceCode, String reason, String idempotencyKey, Long operatorId) {
        BigDecimal before = wallet.getBalanceAmount();
        BigDecimal frozen = wallet.getFrozenAmount() == null ? BigDecimal.ZERO : wallet.getFrozenAmount();
        BigDecimal available = before.subtract(frozen);
        boolean allowNegative = "1".equals(wallet.getNegativeAllowed());
        if (available.compareTo(amount) < 0 && !allowNegative) {
            throw new ServiceException("钱包余额不足, 当前可用: " + available.setScale(4, RoundingMode.HALF_UP));
        }
        BigDecimal after = before.subtract(amount);
        updateBalance(wallet, after);
        insertLedger(wallet, bizType, bizId, serviceCode, DIRECTION_OUT, amount,
            before, after, idempotencyKey, operatorId, reason);
    }

    /**
     * 乐观锁更新余额。
     */
    private void updateBalance(DcsTenantWallet wallet, BigDecimal newBalance) {
        int version = wallet.getVersion() == null ? 0 : wallet.getVersion();
        int affected = walletMapper.update(null, new LambdaUpdateWrapper<DcsTenantWallet>()
            .eq(DcsTenantWallet::getWalletId, wallet.getWalletId())
            .eq(DcsTenantWallet::getVersion, version)
            .set(DcsTenantWallet::getBalanceAmount, newBalance)
            .set(DcsTenantWallet::getVersion, version + 1));
        if (affected == 0) {
            throw new ServiceException("钱包余额更新失败(并发冲突), 请重试");
        }
        wallet.setBalanceAmount(newBalance);
        wallet.setVersion(version + 1);
    }

    /**
     * 条件更新原子扣减(批D D1-③): 余额足额才扣, 防并发透支; affected=0 即不足(或被抢先)。
     *
     * <p>流水期初不取扣减前的快照读(并发下会陈旧), 而是扣减后同事务回读余额反推
     * (after + amount)——行锁未释放前的回读必为本事务结果, 链式流水天然连续。</p>
     */
    private void doDeductConditional(DcsTenantWallet wallet, BigDecimal amount, Long bizId,
                                     String serviceCode, String idempotencyKey) {
        int affected = walletMapper.conditionalDeduct(wallet.getWalletId(), amount);
        if (affected == 0) {
            BigDecimal available = availableBalanceOf(wallet.getTenantId());
            throw new ServiceException("AI 助手额度不足，当前可用余额 " + available.toPlainString()
                + " 元，请联系管理员充值后再试");
        }
        DcsTenantWallet fresh = walletMapper.selectById(wallet.getWalletId());
        BigDecimal after = fresh.getBalanceAmount().setScale(4, RoundingMode.HALF_UP);
        BigDecimal before = after.add(amount);
        insertLedger(wallet, "DEDUCT", bizId, serviceCode, DIRECTION_OUT, amount,
            before, after, idempotencyKey, safeOperatorId(), "AI助手调用扣费");
    }

    /**
     * 组装并写入一条钱包流水(reason 写入 extraJson)。
     */
    private void insertLedger(DcsTenantWallet wallet, String bizType, Long bizId, String serviceCode,
                              String direction, BigDecimal amount, BigDecimal before, BigDecimal after,
                              String idempotencyKey, Long operatorId, String reason) {
        DcsTenantWalletLedger ledger = new DcsTenantWalletLedger();
        ledger.setTenantId(wallet.getTenantId());
        ledger.setWalletId(wallet.getWalletId());
        ledger.setBizType(bizType);
        ledger.setBizId(bizId);
        ledger.setServiceCode(serviceCode);
        ledger.setDirection(direction);
        ledger.setAmount(amount);
        ledger.setCurrency(wallet.getCurrency());
        ledger.setBalanceBefore(before);
        ledger.setBalanceAfter(after);
        ledger.setIdempotencyKey(idempotencyKey);
        ledger.setOperatorId(operatorId);
        if (StrUtil.isNotBlank(reason)) {
            ledger.setExtraJson("{\"reason\":\"" + reason.replace("\"", "\\\"") + "\"}");
        }
        ledgerMapper.insert(ledger);
    }

    /**
     * 按幂等键查询已存在流水(用于去重)。
     */
    private DcsTenantWalletLedger findLedgerByIdempotency(String idempotencyKey) {
        if (StrUtil.isBlank(idempotencyKey)) {
            return null;
        }
        return ledgerMapper.selectOne(new LambdaQueryWrapper<DcsTenantWalletLedger>()
            .eq(DcsTenantWalletLedger::getIdempotencyKey, idempotencyKey)
            .eq(DcsTenantWalletLedger::getDelFlag, "0"));
    }

    /**
     * 按主键加载钱包实体, 不存在抛 ServiceException。
     * 深扫回修(P0-2): 归属校验收口在此——充值/扣减/阈值/状态等按 walletId 的操作全走这里。
     */
    private DcsTenantWallet loadAndCheck(Long walletId) {
        DcsTenantWallet wallet = walletMapper.selectById(walletId);
        if (wallet == null) {
            throw new ServiceException("钱包不存在");
        }
        assertTenantScope(wallet.getTenantId());
        return wallet;
    }

    /**
     * 深扫回修(2026-10-16 P0-2): 读路径租户收窄——超管可指定任意租户(空=全平台),
     * 非超管一律取登录租户, 传参不可放大范围。
     */
    private String scopeTenantId(String tenantId) {
        return LoginHelper.isSuperAdmin() ? tenantId : LoginHelper.getTenantId();
    }

    /**
     * 深扫回修(2026-10-16 P0-2): 按 walletId 读写前的归属校验——非超管只允许操作本租户钱包
     * (登录上下文取不到租户ID时同样拒绝, fail-closed)。
     */
    private void assertTenantScope(String walletTenantId) {
        if (LoginHelper.isSuperAdmin()) {
            return;
        }
        String own = LoginHelper.getTenantId();
        if (StrUtil.isBlank(own) || !own.equals(walletTenantId)) {
            throw new ServiceException("无权操作其它租户的钱包");
        }
    }

    /**
     * 安全获取当前登录用户 ID(未登录或异常时返回 null)。
     */
    private Long safeOperatorId() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
