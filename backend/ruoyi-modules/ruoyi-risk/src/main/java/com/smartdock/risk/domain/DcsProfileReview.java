package com.smartdock.risk.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 用户画像人工复核记录实体，对应数据表 {@code dcs_profile_review}。
 * <p>
 * 当评分规则判定某用户画像处于 HIGH 风险时，系统自动创建一条 PENDING 复核记录，
 * 由管理员在后台给出最终结论 (CONFIRMED 确认风险 / DISMISSED 驳回)。
 * 该实体始终只承载人工复核的元数据，<b>不包含也不修改</b>底层安全评分。
 * <ul>
 *   <li>通过 {@code scoreId} + {@code ruleVersion} 关联到触发本次复核的具体评分快照</li>
 *   <li>支持 PENDING → CONFIRMED/DISMISSED 单向闭环流转; SUPERSEDED 为 2026-08-23 队列归并产生的中间态(已被更新快照取代)</li>
 * </ul>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_profile_review")
public class DcsProfileReview extends TenantEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    /** 复核记录主键 */
    @TableId("review_id")
    private Long reviewId;
    /** 被复核用户 ID */
    private Long userId;
    /** 触发本次复核的评分记录 ID */
    private Long scoreId;
    /** 触发本次复核时的规则版本号 */
    private String ruleVersion;
    /** 画像风险等级: HIGH 高 / MEDIUM 中 / LOW 低 */
    private String profileRiskLevel;
    /** 复核状态: PENDING 待复核 / CONFIRMED 已确认 / DISMISSED 已驳回 / SUPERSEDED 已被更新快照取代 */
    private String status;
    /** 系统自动生成的触发原因说明 */
    private String triggerReason;
    /** 复核结论 (CONFIRMED 确认风险 / DISMISSED 驳回) */
    private String conclusion;
    /** 复核理由 (管理员填写) */
    private String reviewReason;
    /** 执行复核的管理员用户 ID */
    private Long reviewedBy;
    /** 复核操作发生时间 */
    private Date reviewedTime;
}
