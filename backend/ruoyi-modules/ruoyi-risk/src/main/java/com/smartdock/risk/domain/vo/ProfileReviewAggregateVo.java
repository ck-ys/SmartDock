package com.smartdock.risk.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 画像复核聚合视图行(2026-08-23 复核页主视图数据源)。
 *
 * <p><b>聚合口径</b>: 同一用户全部 PENDING 快照聚合为一行——
 * 展示该用户<b>最新一条</b>快照的评分字段, 外加积压快照数与首末入队时间;
 * 配合 review-user 端点(按人一键复核)消化同人全部积压。</p>
 *
 * <p>字段由 {@code DcsProfileReviewMapper.selectPendingAggregate} 的 SQL 别名直接映射。</p>
 *
 * @author smartdock
 */
@Data
public class ProfileReviewAggregateVo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** 被复核用户ID */
    private Long userId;
    /** 用户名(enrich 填充, 非SQL映射) */
    private String userName;
    /** 用户昵称(enrich 填充, 非SQL映射) */
    private String nickName;
    /** 最新一条待复核记录ID */
    private Long latestReviewId;
    /** 最新快照对应的评分记录ID */
    private Long scoreId;
    /** 最新快照评分规则版本 */
    private String ruleVersion;
    /** 画像风险等级(恒为 HIGH, 入队条件) */
    private String profileRiskLevel;
    /** 最新快照触发原因 */
    private String triggerReason;
    /** 该用户 PENDING 快照总数(积压数) */
    private Long pendingCount;
    /** 最早入队时间 */
    private Date firstTime;
    /** 最近活动时间(首次入队或快照被覆盖刷新, 取 COALESCE(update_time, create_time)) */
    private Date lastTime;
}
