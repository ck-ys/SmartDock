package com.smartdock.risk.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.risk.domain.DcsProfileReview;
import com.smartdock.risk.domain.vo.ProfileReviewAggregateVo;
import com.smartdock.risk.domain.vo.ProfileReviewVo;

import java.util.List;

/**
 * 用户画像人工复核记录 Mapper, 继承 BaseMapperPlus&lt;DcsProfileReview, ProfileReviewVo&gt;。
 *
 * <p>2026-08-23 入队语义改造(复核队列根治):
 * <ul>
 *   <li>{@link #upsertPending} 用部分唯一索引 <code>(tenant_id, user_id) WHERE status='PENDING'</code>
 *       做冲突仲裁——同人已有待复核时新评分快照直接覆盖旧快照, 不再按评分快照堆叠行
 *       (改造前按 (tenant_id, score_id) 每次评分入一条, 实测积压 4.4 万条/仅 86 人)。</li>
 *   <li>{@link #countPendingUsers} 口径=待复核<b>用户</b>数(同人只算一个), 供大盘卡片使用。</li>
 * </ul></p>
 *
 * @author smartdock
 */
public interface DcsProfileReviewMapper extends BaseMapperPlus<DcsProfileReview, ProfileReviewVo> {

    /**
     * 同人单条 PENDING upsert。
     *
     * <p>冲突时更新 score_id/rule_version/trigger_reason(新快照覆盖旧快照), 返回 1=插入或覆盖, 语义统一记"入队/刷新"。</p>
     */
    @Insert("""
        INSERT INTO dcs_profile_review (
            review_id, tenant_id, user_id, score_id, rule_version, profile_risk_level,
            status, trigger_reason, create_dept, create_by, create_time, update_by, update_time
        ) VALUES (
            #{reviewId}, #{tenantId}, #{userId}, #{scoreId}, #{ruleVersion}, #{profileRiskLevel},
            #{status}, #{triggerReason}, #{createDept}, #{createBy}, COALESCE(#{createTime}, now()),
            #{updateBy}, #{updateTime}
        )
        ON CONFLICT (tenant_id, user_id) WHERE status = 'PENDING' DO UPDATE SET
            score_id = EXCLUDED.score_id,
            rule_version = EXCLUDED.rule_version,
            trigger_reason = EXCLUDED.trigger_reason,
            update_by = EXCLUDED.update_by,
            update_time = now()
        """)
    int upsertPending(DcsProfileReview review);

    /** 待复核用户数(同人只算一个), 大盘卡片口径。 */
    @Select("SELECT COUNT(DISTINCT user_id) FROM dcs_profile_review WHERE tenant_id = #{tenantId} AND status = 'PENDING'")
    long countPendingUsers(@Param("tenantId") String tenantId);

    /**
     * 画像复核聚合视图: 同人一行(最新快照字段 + 积压快照数 + 首末时间), 按最近活动倒序。
     * <p>last_time 取 GREATEST(update_time, create_time): upsert 覆盖只刷 update_time,
     * 而 update_time(JVM UTC-4 时钟)可能早于 create_time(DB 时钟)——已知 4h 时差问题家族,
     * GREATEST 保证行内 last_time>=first_time 不倒挂(跨用户排序以 create 时钟为主)。</p>
     */
    @Select("""
        SELECT user_id AS userId, review_id AS latestReviewId, score_id AS scoreId,
               rule_version AS ruleVersion, profile_risk_level AS profileRiskLevel,
               trigger_reason AS triggerReason, pending_count AS pendingCount,
               first_time AS firstTime, last_time AS lastTime
        FROM (
            SELECT DISTINCT ON (user_id) user_id, review_id, score_id, rule_version,
                   profile_risk_level, trigger_reason,
                   GREATEST(COALESCE(update_time, create_time), create_time) AS last_time,
                   COUNT(*)    OVER (PARTITION BY user_id) AS pending_count,
                   MIN(create_time) OVER (PARTITION BY user_id) AS first_time
            FROM dcs_profile_review
            WHERE tenant_id = #{tenantId} AND status = 'PENDING'
            ORDER BY user_id, create_time DESC, review_id DESC
        ) t
        ORDER BY last_time DESC
        """)
    List<ProfileReviewAggregateVo> selectPendingAggregate(@Param("tenantId") String tenantId);
}
