package com.smartdock.risk.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 风控处置记录实体，对应数据表 {@code dcs_risk_action}。
 * <p>
 * 当 {@code RiskScoreService.assess} 命中 L3/L4 风险等级时，由
 * {@code RiskActionService.syncAssessment} 同步生成一条可逆处置记录
 * (如 TEMP_FREEZE 临时冻结、FORCE_LOGOUT 强制下线)。该实体仅承载处置动作的
 * 元数据与生命周期，不存储也不影响安全评分本身。
 * <ul>
 *   <li>通过 {@code triggerKey} (格式 userId:actionType) 实现幂等控制，避免重复处置</li>
 *   <li>支持 ACTIVE → EXPIRED/RELEASED 的状态流转与人工复核 (PENDING/APPROVED/DISMISSED) 闭环</li>
 *   <li>{@code ipMasked} 已做脱敏处理，不落盘明文 IP</li>
 * </ul>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_risk_action")
public class DcsRiskAction extends TenantEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    /** 处置记录主键 */
    @TableId("action_id")
    private Long actionId;
    /** 被处置用户 ID */
    private Long userId;
    /** 脱敏后的 IP (不落盘明文) */
    private String ipMasked;
    /** 触发本次处置的风险等级 L0-L4 */
    private Integer riskLevel;
    /** 触发时刻的风险评分 */
    private Integer riskScore;
    /** 处置来源链路: GLOBAL (全局策略) / RULE (规则命中) */
    private String sourceTrack;
    /** 处置动作类型 (TEMP_FREEZE 临时冻结 / FORCE_LOGOUT 强制下线 等) */
    private String actionType;
    /** 幂等键，格式 userId:actionType，用于避免对同一用户重复下发相同处置 */
    private String triggerKey;
    /** 触发本次处置的可读原因说明 */
    private String triggerReason;
    /** 处置状态: ACTIVE 生效中 / EXPIRED 已到期 / RELEASED 已人工释放 */
    private String status;
    /** 处置生效时间 */
    private Date startTime;
    /** 处置到期时间 (到期后自动转为 EXPIRED) */
    private Date expireTime;
    /** 执行释放操作的管理员用户 ID */
    private Long releasedBy;
    /** 释放操作发生时间 */
    private Date releasedTime;
    /** 释放原因 (管理员填写) */
    private String releaseReason;
    /** 人工复核状态: PENDING 待复核 / APPROVED 已确认 / DISMISSED 已驳回 */
    private String reviewStatus;
    /** 执行复核的管理员用户 ID */
    private Long reviewedBy;
    /** 复核操作发生时间 */
    private Date reviewedTime;
    /** 复核结论 (CONFIRMED 确认 / DISMISSED 驳回) */
    private String reviewConclusion;
    /** 复核理由 (管理员填写) */
    private String reviewReason;
}
