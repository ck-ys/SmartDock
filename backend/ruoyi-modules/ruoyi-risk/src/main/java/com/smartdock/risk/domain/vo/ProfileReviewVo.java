package com.smartdock.risk.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 用户画像人工复核记录展示对象(对应表 dcs_profile_review)。
 *
 * <p><b>何时生成</b>: 评分规则算出画像风险等级达到阈值时, 系统自动生成一条 PENDING 复核记录,
 * 由安全管理员人工给出最终结论(CONFIRMED/DISMISSED), 用于在自动评分之上叠加人工裁决。</p>
 *
 * <p><b>主要字段</b>:</p>
 * <ul>
 *   <li>profileRiskLevel: 画像风险等级, HIGH/MEDIUM/LOW</li>
 *   <li>status: 复核状态, PENDING(待复核) / CONFIRMED(确认风险) / DISMISSED(驳回) / SUPERSEDED(已被更新快照取代)</li>
 *   <li>triggerReason: 触发原因, 命中规则的描述</li>
 *   <li>conclusion / reviewReason: 管理员人工填写的结论与理由(留痕)</li>
 * </ul>
 *
 * @author smartdock
 */
@Data
public class ProfileReviewVo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private Long reviewId;
    private Long userId;
    private String userName;
    private String nickName;
    private Long scoreId;
    private String ruleVersion;
    private String profileRiskLevel;
    private String status;
    private String triggerReason;
    private String conclusion;
    private String reviewReason;
    private Long reviewedBy;
    private Date reviewedTime;
    private Date createTime;
}
