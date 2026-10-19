package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.smartdock.domain.vo.WalletReconcileAssistantVo;
import com.smartdock.domain.vo.WalletReconcileChainBreakVo;
import com.smartdock.domain.vo.WalletReconcileDeductNullVo;
import com.smartdock.domain.vo.WalletReconcileNetVo;

import java.util.List;

/**
 * 钱包对账巡检 Mapper(批C 2026-09-24): 三项只读检查的自定义聚合 SQL。
 *
 * <p>钱包/流水表同属跨租户管理(定时线程无租户上下文), 与
 * {@link DcsTenantWalletMapper} 一致关闭租户拦截器; SQL 在
 * resources/mapper/dcs/WalletReconcileMapper.xml。</p>
 *
 * @author smartdock
 */
@InterceptorIgnore(tenantLine = "true")
public interface WalletReconcileMapper {

    /**
     * 检查①: 逐钱包 余额 vs 流水净额(Σ IN - Σ OUT)。
     */
    List<WalletReconcileNetVo> selectWalletNet();

    /**
     * 检查②: 流水链断裂行(同钱包按 ledger_id 排序, balance_before ≠ 前笔 balance_after)。
     */
    List<WalletReconcileChainBreakVo> selectChainBreaks();

    /**
     * 检查③: 按租户统计 DEDUCT 且 biz_id 为空的流水条数(监控修复前存量是否被突破)。
     */
    List<WalletReconcileDeductNullVo> selectDeductNullByTenant();

    /**
     * 检查④(批D D1-⑤): 助手会话计费对账——ASSISTANT_CHAT 成功单 vs AI_ASSISTANT_CHAT 流水
     * (漏扣异常数 + 双方金额合计, 金额按坑68 同精度四舍五入到 4 位比对)。
     */
    List<WalletReconcileAssistantVo> selectAssistantUsageLedgerDiff();
}
