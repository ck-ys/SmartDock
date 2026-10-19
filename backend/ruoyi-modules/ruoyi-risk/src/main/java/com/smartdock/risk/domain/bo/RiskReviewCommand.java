package com.smartdock.risk.domain.bo;

import jakarta.validation.constraints.NotBlank;

/**
 * 风控复核/释放操作命令 BO，用于后台管理员对处置动作 (DcsRiskAction) 给出最终结论或进行释放。
 * <p>
 * 写接口只接受此 BO，<b>不直接接受</b>持久化实体，避免外部传入越权字段。
 * <ul>
 *   <li>{@code conclusion}: 复核结论，CONFIRMED 确认风险 / DISMISSED 驳回</li>
 *   <li>{@code reason}: 操作理由，必填，需经 {@code RiskActionPolicy.requireReason} 长度校验</li>
 * </ul>
 *
 * @author smartdock
 */
public record RiskReviewCommand(
    /**
     * 复核结论，取值 CONFIRMED (确认风险存在) 或 DISMISSED (驳回)，
     * 由 {@code RiskActionPolicy.requireConclusion} 做白名单校验。
     */
    String conclusion,
    /** 操作理由 (必填，最长 500 字) */
    @NotBlank(message = "操作理由不能为空") String reason
) {
}
