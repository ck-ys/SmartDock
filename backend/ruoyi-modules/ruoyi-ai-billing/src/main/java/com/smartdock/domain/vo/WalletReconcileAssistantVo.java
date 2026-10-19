package com.smartdock.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 钱包对账检查④结果行(批D D1-⑤): 助手会话计费 usage ↔ ledger 对账。
 *
 * @author smartdock
 */
@Data
public class WalletReconcileAssistantVo {

    /** 租户 */
    private String tenantId;

    /** 成功的 ASSISTANT_CHAT 调用单数 */
    private Long usageCount;

    /** 漏扣异常数: 有成本、无流水、且无"有意免扣"标记(非本币/余额不足均打了标记) */
    private Long deductMissing;

    /** 非本币有意免扣数(免费敞口可见档, glm53/dsflash 共识: 不混入漏扣也不静默) */
    private Long nonCnyExempt;

    /** 已扣单据金额合计(usage.cost 四舍五入到分位, 与扣款同口径) */
    private BigDecimal usageSum;

    /** AI_ASSISTANT_CHAT 服务码流水金额合计 */
    private BigDecimal ledgerSum;
}
