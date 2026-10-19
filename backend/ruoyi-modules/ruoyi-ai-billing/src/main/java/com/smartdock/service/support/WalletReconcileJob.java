package com.smartdock.service.support;

import com.baomidou.lock.annotation.Lock4j;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.config.WalletReconcileProperties;
import com.smartdock.domain.vo.WalletReconcileAssistantVo;
import com.smartdock.domain.vo.WalletReconcileChainBreakVo;
import com.smartdock.domain.vo.WalletReconcileDeductNullVo;
import com.smartdock.domain.vo.WalletReconcileNetVo;
import com.smartdock.mapper.WalletReconcileMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 钱包对账巡检(批C 2026-09-24 上线, 拍板 Q2:A <b>只报警不动账</b>; 批1 2026-09-25 基线配置化)。
 *
 * <p>每晚 02:30(错开 09:00 低余额扫描)执行三项只读检查:</p>
 * <ol>
 *   <li>逐钱包 余额 vs 流水净额(Σ IN - Σ OUT)——余额被绕过流水改动会在此暴露;</li>
 *   <li>流水链连续性(同钱包按 ledger_id 排序, 本笔 balance_before ≠ 前笔 balance_after);</li>
 *   <li>DEDUCT 空 biz_id 计数——biz_id 接线修复前的历史存量监控, 计数漂移即新异常;</li>
 *   <li>助手会话计费对账(批D D1-⑤ 检查④)——ASSISTANT_CHAT 成功单 vs AI_ASSISTANT_CHAT 流水,
 *       漏扣(无流水且无免扣标记)或金额不平即告警。</li>
 * </ol>
 *
 * <p><b>基线环境化(批1)</b>: 三项白名单从代码常量改为 {@link WalletReconcileProperties} 配置注入——
 * 开发配置带历史存量基线, 交付配置零基线=客户环境从零对账, 详见该配置类 javadoc。</p>
 *
 * <p>告警链: 差异 → {@link WalletAlertService#publishReconcileAlert}(事务内) →
 * WalletAlertEvent(WALLET_RECONCILE_MISMATCH) → dcs 侧 WalletAlertNoticeAdapter 同步翻译 →
 * 站内信风险分组。该字面量被 DcsNoticeMapper.xml/DcsNoticeServiceImpl/前端 noticePresentation
 * 硬编码过滤, 禁改字。未处理差异每晚重复告警(有意设计, 对账守卫不静默)。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletReconcileJob {

    /** 对账 Mapper(三项检查只读 SQL) */
    private final WalletReconcileMapper reconcileMapper;
    /** 钱包告警服务(事务内发布对账告警) */
    private final WalletAlertService walletAlertService;
    /** 对账基线配置(环境化: dev 带历史存量/交付零基线) */
    private final WalletReconcileProperties reconcileProperties;

    /**
     * 每晚 02:30 对账巡检(失败只记日志, 不抛出——定时线程抛异常只会刷屏)。
     * 批2 拍板 Q6:A: @Lock4j 跨节点互斥(范式同 DcsNoticeOutboxRetryScanner), 防多实例双跑双告警。
     */
    @Scheduled(cron = "0 30 2 * * ?")
    @Lock4j(keys = {"'wallet-reconcile-job'"})
    public void reconcile() {
        try {
            Map<Long, BigDecimal> knownBalanceDiffs = reconcileProperties.getBalanceDiffs();
            Set<Long> knownChainBreakIds = reconcileProperties.getChainBreakLedgerIds();
            Map<String, Long> deductBaseline = reconcileProperties.getDeductNullBaseline();
            List<WalletReconcileNetVo> newBalanceDiffs =
                filterNewBalanceDiffs(reconcileMapper.selectWalletNet(), knownBalanceDiffs);
            List<WalletReconcileChainBreakVo> newBreaks =
                filterNewChainBreaks(reconcileMapper.selectChainBreaks(), knownChainBreakIds);
            List<WalletReconcileDeductNullVo> newDeductNulls =
                filterNewDeductNulls(reconcileMapper.selectDeductNullByTenant(), deductBaseline);
            List<WalletReconcileAssistantVo> assistantDiffs =
                filterNewAssistantDiffs(reconcileMapper.selectAssistantUsageLedgerDiff());
            if (newBalanceDiffs.isEmpty() && newBreaks.isEmpty() && newDeductNulls.isEmpty()
                && assistantDiffs.isEmpty()) {
                log.info("[WalletReconcile] 对账通过: 余额净额一致/链连续/DEDUCT空biz_id与基线一致");
                return;
            }
            // 按租户聚合差异明细与计数, 一租户一条告警(extra 计数同桶, 不掺别租户)
            Map<String, List<String>> tenantFindings = new LinkedHashMap<>();
            Map<String, Integer> tenantBalanceDiffs = new LinkedHashMap<>();
            Map<String, Integer> tenantChainBreaks = new LinkedHashMap<>();
            Map<String, Integer> tenantDeductNulls = new LinkedHashMap<>();
            Map<String, Integer> tenantAssistantDiffs = new LinkedHashMap<>();
            for (WalletReconcileNetVo row : newBalanceDiffs) {
                BigDecimal diff = row.getBalanceAmount().subtract(row.getLedgerNet());
                addFinding(tenantFindings, tenantBalanceDiffs, row.getTenantId(), String.format(
                    "钱包 %d 余额(%s)与流水净额差 %s(已知基线 %s)", row.getWalletId(),
                    row.getBalanceAmount().toPlainString(), diff.toPlainString(),
                    knownBalanceDiffs.getOrDefault(row.getWalletId(), BigDecimal.ZERO).toPlainString()));
            }
            for (WalletReconcileChainBreakVo row : newBreaks) {
                addFinding(tenantFindings, tenantChainBreaks, row.getTenantId(), String.format(
                    "流水链断裂: ledger %d 期初 %s ≠ 前笔期末 %s", row.getLedgerId(),
                    row.getBalanceBefore().toPlainString(), row.getPrevAfter().toPlainString()));
            }
            for (WalletReconcileDeductNullVo row : newDeductNulls) {
                addFinding(tenantFindings, tenantDeductNulls, row.getTenantId(), String.format(
                    "DEDUCT 空 biz_id 流水 %d 条(基线 %d)", row.getNullBizCount(),
                    deductBaseline.getOrDefault(row.getTenantId(), 0L)));
            }
            for (WalletReconcileAssistantVo row : assistantDiffs) {
                addFinding(tenantFindings, tenantAssistantDiffs, row.getTenantId(), String.format(
                    "助手计费不平: 单据 %d 笔/漏扣 %d 笔/非本币免扣 %d 笔/单据合计 %s vs 流水合计 %s",
                    row.getUsageCount(), row.getDeductMissing(),
                    row.getNonCnyExempt() == null ? 0 : row.getNonCnyExempt(),
                    row.getUsageSum().toPlainString(), row.getLedgerSum().toPlainString()));
            }
            tenantFindings.keySet().forEach(tenantId -> walletAlertService.publishReconcileAlert(
                tenantId, "钱包对账发现新差异",
                "巡检发现以下白名单外差异(只报警不动账, 请核查):\n" + String.join("\n", tenantFindings.get(tenantId)),
                Map.of("balanceDiffs", tenantBalanceDiffs.getOrDefault(tenantId, 0),
                    "chainBreaks", tenantChainBreaks.getOrDefault(tenantId, 0),
                    "deductNullTenants", tenantDeductNulls.getOrDefault(tenantId, 0),
                    "assistantDiffs", tenantAssistantDiffs.getOrDefault(tenantId, 0))));
            log.warn("[WalletReconcile] 对账发现白名单外差异: 余额差额钱包 {} 个/链断裂 {} 笔/DEDUCT空biz_id租户 {} 个/助手计费不平 {} 个, 已告警",
                newBalanceDiffs.size(), newBreaks.size(), newDeductNulls.size(), assistantDiffs.size());
        } catch (Exception e) {
            log.error("[WalletReconcile] 对账巡检执行失败", e);
        }
    }

    /** 差额为 0 不是发现; 基线钱包差额与基线一致静默, 漂移(含归零回正)算新差异。 */
    static List<WalletReconcileNetVo> filterNewBalanceDiffs(List<WalletReconcileNetVo> rows,
                                                            Map<Long, BigDecimal> knownBalanceDiffs) {
        List<WalletReconcileNetVo> findings = new ArrayList<>();
        for (WalletReconcileNetVo row : rows) {
            BigDecimal diff = row.getBalanceAmount().subtract(row.getLedgerNet());
            if (diff.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            BigDecimal known = knownBalanceDiffs.get(row.getWalletId());
            if (known == null || known.compareTo(diff) != 0) {
                findings.add(row);
            }
        }
        return findings;
    }

    /** 白名单内的历史断裂行静默, 新断裂行全报。 */
    static List<WalletReconcileChainBreakVo> filterNewChainBreaks(List<WalletReconcileChainBreakVo> rows,
                                                                  Set<Long> knownChainBreakIds) {
        return rows.stream()
            .filter(row -> !knownChainBreakIds.contains(row.getLedgerId()))
            .toList();
    }

    /** 计数与基线一致静默; 漂移(增加=新漏单, 减少=存量被改动)都算新异常; 基线租户从结果集消失=一次性清零反向兜底。 */
    static List<WalletReconcileDeductNullVo> filterNewDeductNulls(List<WalletReconcileDeductNullVo> rows,
                                                                  Map<String, Long> deductBaseline) {
        List<WalletReconcileDeductNullVo> findings = new ArrayList<>();
        Set<String> presentTenants = new HashSet<>();
        for (WalletReconcileDeductNullVo row : rows) {
            presentTenants.add(row.getTenantId());
            long baseline = deductBaseline.getOrDefault(row.getTenantId(), 0L);
            if (row.getNullBizCount() != baseline) {
                findings.add(row);
            }
        }
        // 基线租户从结果集消失=存量被一次性清零(GROUP BY 不产 count=0 行), 反向比对兜住
        for (Map.Entry<String, Long> entry : deductBaseline.entrySet()) {
            if (!presentTenants.contains(entry.getKey())) {
                WalletReconcileDeductNullVo vanished = new WalletReconcileDeductNullVo();
                vanished.setTenantId(entry.getKey());
                vanished.setNullBizCount(0L);
                findings.add(vanished);
            }
        }
        return findings;
    }

    /**
     * 检查④(批D D1-⑤): 漏扣>0 / 单据流水金额不平(同精度比对) / 非本币免扣>0(敞口可见) 即发现;
     * 该维全新无历史基线。
     */
    static List<WalletReconcileAssistantVo> filterNewAssistantDiffs(List<WalletReconcileAssistantVo> rows) {
        List<WalletReconcileAssistantVo> findings = new ArrayList<>();
        for (WalletReconcileAssistantVo row : rows) {
            if (row.getDeductMissing() != null && row.getDeductMissing() > 0) {
                findings.add(row);
                continue;
            }
            if (row.getNonCnyExempt() != null && row.getNonCnyExempt() > 0) {
                findings.add(row);
                continue;
            }
            if (row.getUsageSum() != null && row.getLedgerSum() != null
                && row.getUsageSum().compareTo(row.getLedgerSum()) != 0) {
                findings.add(row);
            }
        }
        return findings;
    }

    private static void addFinding(Map<String, List<String>> tenantFindings, Map<String, Integer> counter,
                                   String tenantId, String line) {
        tenantFindings.computeIfAbsent(tenantId, k -> new ArrayList<>()).add(line);
        counter.merge(tenantId, 1, Integer::sum);
    }
}
