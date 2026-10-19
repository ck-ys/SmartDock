package com.smartdock.risk.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 风控处置记录展示对象(对应表 dcs_risk_action)。
 *
 * <p><b>何时生成</b>: 当 {@link com.smartdock.risk.service.RiskScoreService#assess} 评估等级达到
 * L3/L4 时, 由 {@link com.smartdock.risk.service.RiskActionService#syncAssessment} 创建对应处置记录
 * (如 TEMP_FREEZE 临时冻结 / FORCE_LOGOUT 强制下线), 实现可逆的自动安全处置。</p>
 *
 * <p><b>主要字段</b>:</p>
 * <ul>
 *   <li>riskLevel: 触发本次处置的风险等级, L0-L4</li>
 *   <li>riskScore: 触发时的风险总分</li>
 *   <li>sourceTrack: 处置来源, GLOBAL(全局策略) / RULE(单条规则)</li>
 *   <li>actionType: 处置类型, 如 TEMP_FREEZE / FORCE_LOGOUT</li>
 *   <li>status: 处置状态, ACTIVE(生效中) / EXPIRED(已自动到期) / RELEASED(已人工解除)</li>
 *   <li>triggerKey: 幂等键 userId:actionType, 配合唯一索引保证同一用户同一类型只一条 ACTIVE</li>
 *   <li>reviewStatus: 人工复核状态, PENDING / CONFIRMED / DISMISSED</li>
 *   <li>ipMasked: 脱敏后的 IP(末段打 *, 兼顾排查与隐私)</li>
 * </ul>
 *
 * @author smartdock
 */
@Data
public class RiskActionVo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private Long actionId;
    private Long userId;
    private String userName;
    private String nickName;
    private String ipMasked;
    private Integer riskLevel;
    private Integer riskScore;
    private String sourceTrack;
    private String actionType;
    private String triggerReason;
    private String status;
    private Date startTime;
    private Date expireTime;
    private Long releasedBy;
    private Date releasedTime;
    private String releaseReason;
    private String reviewStatus;
    private Long reviewedBy;
    private Date reviewedTime;
    private String reviewConclusion;
    private String reviewReason;
    private Boolean enforcementEnabled;
}
