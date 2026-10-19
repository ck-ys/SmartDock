package com.smartdock.risk.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 按用户复核命令 BO(2026-08-23 复核队列治理配套)。
 *
 * <p>把指定用户<b>全部</b> PENDING 画像一次性给出结论, 用于复核页聚合视图
 * "复核全部"操作——同人多次评分已合并为一行, 按人消化积压。</p>
 * <ul>
 *   <li>{@code userId}: 被复核用户, 必填</li>
 *   <li>{@code conclusion}: 复核结论, CONFIRMED 确认风险 / DISMISSED 驳回</li>
 *   <li>{@code reason}: 复核理由, 必填, 留痕</li>
 * </ul>
 *
 * @author smartdock
 */
public record RiskUserReviewCommand(
    /** 被复核用户 ID */
    @NotNull(message = "用户ID不能为空") Long userId,
    /** 复核结论, CONFIRMED / DISMISSED, 经 RiskActionPolicy.requireConclusion 白名单校验 */
    @NotBlank(message = "复核结论不能为空") String conclusion,
    /** 复核理由(必填, 留痕) */
    @NotBlank(message = "复核理由不能为空") String reason
) {
}
