package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 低分复核队列 profile_review_queue(低分主体自动入队, 人工确认/驳回)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_review_queue")
public class ProfileReviewQueue extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "review_id", type = IdType.ASSIGN_ID)
    private Long reviewId;

    private String tenantId;

    private String source;

    private String subjectType;

    private String subjectId;

    private String displayName;

    private Long snapshotId;

    private BigDecimal totalScore;

    /** HIGH / MEDIUM / LOW */
    private String riskLevel;

    private String triggerReason;

    /** PENDING / CONFIRMED / DISMISSED / SUPERSEDED */
    private String status;

    /** CONFIRMED / DISMISSED */
    private String conclusion;

    private String reviewNote;

    private Long reviewedBy;

    private Date reviewedAt;

    /** 超时天数(现算, 批9 Q4; >0 即超 SLA) */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Long overdueDays;

    /** 该来源的 SLA 天数(现算透出) */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Integer slaDays;
}
