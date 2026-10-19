package com.smartdock.profile.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import com.smartdock.profile.event.ProfileSubject;
import com.smartdock.profile.query.ProfileOverview;
import com.smartdock.profile.query.ProfileRankingRow;
import com.smartdock.profile.query.ProfileSubjectQuery;
import com.smartdock.profile.query.ProfileSubjectRow;

import java.util.List;

/**
 * 画像主体 Mapper。
 *
 * <p>列表/排行用 LATERAL 取每个主体最新快照，避免“先取主体再 N 次查快照”。</p>
 *
 * @author smartdock
 */
@Mapper
public interface ProfileSubjectMapper extends BaseMapperPlus<ProfileSubject, ProfileSubject> {

    /**
     * 带最新快照的主体分页。
     */
    @Select("""
        <script>
        SELECT sub.id, sub.tenant_id, sub.source, sub.subject_type, sub.subject_id,
               sub.display_name, sub.first_event_time, sub.last_event_time, sub.event_count,
               sub.properties::text AS properties,
               s.total_score, s.dimension_detail::text AS dimension_detail,
               s.rule_version, s.calc_time
        FROM profile_subject sub
        LEFT JOIN LATERAL (
            SELECT total_score, dimension_detail, rule_version, calc_time
            FROM profile_score_snapshot snap
            WHERE snap.tenant_id = sub.tenant_id AND snap.source = sub.source
              AND snap.subject_type = sub.subject_type AND snap.subject_id = sub.subject_id
              AND snap.del_flag = '0'
            ORDER BY snap.calc_time DESC, snap.snapshot_id DESC
            LIMIT 1
        ) s ON TRUE
        WHERE sub.tenant_id = #{tenantId} AND sub.source = #{q.source} AND sub.del_flag = '0'
        <if test="q.subjectType != null and q.subjectType != ''">
          AND sub.subject_type = #{q.subjectType}
        </if>
        <if test="q.keyword != null and q.keyword != ''">
          AND (sub.subject_id ILIKE CONCAT('%', #{q.keyword}, '%')
               OR sub.display_name ILIKE CONCAT('%', #{q.keyword}, '%'))
        </if>
        <if test="q.minScore != null">
          AND s.total_score &gt;= #{q.minScore}
        </if>
        <if test="q.maxScore != null">
          AND s.total_score &lt;= #{q.maxScore}
        </if>
        ORDER BY
        <choose>
          <when test="q.sortBy == 'score'">s.total_score</when>
          <when test="q.sortBy == 'events'">sub.event_count</when>
          <when test="q.sortBy == 'name'">sub.display_name</when>
          <otherwise>sub.last_event_time</otherwise>
        </choose>
        <choose>
          <when test="q.sortOrder == 'asc'">ASC</when>
          <otherwise>DESC</otherwise>
        </choose>
        NULLS LAST, sub.id DESC
        </script>
        """)
    IPage<ProfileSubjectRow> selectSubjectPage(IPage<ProfileSubjectRow> page,
                                               @Param("tenantId") String tenantId,
                                               @Param("q") ProfileSubjectQuery query);

    /**
     * 最新快照排行: dimension 为空按总分, 否则按该维度分数。
     */
    @Select("""
        <script>
        SELECT sub.subject_type, sub.subject_id, sub.display_name, s.total_score,
        <choose>
          <when test="dimension != null and dimension != ''">
               (s.dimension_detail-&gt;#{dimension}-&gt;&gt;'score')::numeric
          </when>
          <otherwise>NULL::numeric</otherwise>
        </choose>
               AS dimension_score,
               sub.event_count, sub.last_event_time, s.calc_time
        FROM profile_subject sub
        LEFT JOIN LATERAL (
            SELECT total_score, dimension_detail, calc_time
            FROM profile_score_snapshot snap
            WHERE snap.tenant_id = sub.tenant_id AND snap.source = sub.source
              AND snap.subject_type = sub.subject_type AND snap.subject_id = sub.subject_id
              AND snap.del_flag = '0'
            ORDER BY snap.calc_time DESC, snap.snapshot_id DESC
            LIMIT 1
        ) s ON TRUE
        WHERE sub.tenant_id = #{tenantId} AND sub.source = #{source} AND sub.del_flag = '0'
          AND s.total_score IS NOT NULL
        <if test="subjectType != null and subjectType != ''">
          AND sub.subject_type = #{subjectType}
        </if>
        <if test="dimension != null and dimension != ''">
          AND jsonb_exists(s.dimension_detail, #{dimension})
        </if>
        ORDER BY
        <choose>
          <when test="dimension != null and dimension != ''">
            (s.dimension_detail-&gt;#{dimension}-&gt;&gt;'score')::numeric DESC NULLS LAST
          </when>
          <otherwise>s.total_score DESC NULLS LAST</otherwise>
        </choose>
        , sub.id DESC
        LIMIT #{limit}
        </script>
        """)
    List<ProfileRankingRow> selectRanking(@Param("tenantId") String tenantId,
                                          @Param("source") String source,
                                          @Param("subjectType") String subjectType,
                                          @Param("dimension") String dimension,
                                          @Param("limit") int limit);

    /**
     * 比给定总分更高的主体数(用于排名)。
     */
    @Select("""
        SELECT COUNT(*) FROM (
            SELECT DISTINCT ON (subject_type, subject_id) total_score
            FROM profile_score_snapshot
            WHERE tenant_id = #{tenantId} AND source = #{source}
              AND subject_type = #{subjectType} AND del_flag = '0'
            ORDER BY subject_type, subject_id, calc_time DESC, snapshot_id DESC
        ) t WHERE t.total_score > #{score}
        """)
    Long countHigherScore(@Param("tenantId") String tenantId,
                          @Param("source") String source,
                          @Param("subjectType") String subjectType,
                          @Param("score") java.math.BigDecimal score);

    /**
     * 画像总览: 主体数/已算分数/平均分/风险分层。
     */
    @Select("""
        SELECT COUNT(*) AS subject_count,
               COUNT(s.total_score) AS scored_count,
               COALESCE(AVG(s.total_score), 0) AS avg_score,
               COUNT(*) FILTER (WHERE s.total_score >= 75) AS low_risk,
               COUNT(*) FILTER (WHERE s.total_score >= 60 AND s.total_score < 75) AS medium_risk,
               COUNT(*) FILTER (WHERE s.total_score < 60) AS high_risk
        FROM profile_subject sub
        LEFT JOIN LATERAL (
            SELECT total_score, dimension_detail, calc_time
            FROM profile_score_snapshot snap
            WHERE snap.tenant_id = sub.tenant_id AND snap.source = sub.source
              AND snap.subject_type = sub.subject_type AND snap.subject_id = sub.subject_id
              AND snap.del_flag = '0'
            ORDER BY snap.calc_time DESC, snap.snapshot_id DESC
            LIMIT 1
        ) s ON TRUE
        WHERE sub.tenant_id = #{tenantId} AND sub.source = #{source} AND sub.del_flag = '0'
        """)
    ProfileOverview selectOverview(@Param("tenantId") String tenantId,
                                   @Param("source") String source);

    /**
     * 分群候选: 主体 + 最新维度快照(全量, 由 Java 规则引擎筛选)。
     */
    @Select("""
        SELECT sub.subject_type, sub.subject_id, sub.display_name, sub.event_count, sub.last_event_time,
               sub.properties::text AS properties,
               s.total_score, s.dimension_detail::text AS dimension_detail
        FROM profile_subject sub
        LEFT JOIN LATERAL (
            SELECT total_score, dimension_detail
            FROM profile_score_snapshot snap
            WHERE snap.tenant_id = sub.tenant_id AND snap.source = sub.source
              AND snap.subject_type = sub.subject_type AND snap.subject_id = sub.subject_id
              AND snap.del_flag = '0'
            ORDER BY snap.calc_time DESC, snap.snapshot_id DESC
            LIMIT 1
        ) s ON TRUE
        WHERE sub.tenant_id = #{tenantId} AND sub.source = #{source} AND sub.del_flag = '0'
        ORDER BY sub.subject_type, sub.subject_id
        """)
    List<ProfileSubjectRow> selectSegmentCandidates(@Param("tenantId") String tenantId,
                                                    @Param("source") String source);

    /**
     * 每个主体最新维度快照(复核扫描用)。
     */
    @Select("""
        SELECT DISTINCT ON (subject_type, subject_id)
               snapshot_id, tenant_id, source, subject_type, subject_id, total_score, calc_time
        FROM profile_score_snapshot
        WHERE tenant_id = #{tenantId} AND source = #{source} AND del_flag = '0'
        ORDER BY subject_type, subject_id, calc_time DESC, snapshot_id DESC
        """)
    List<com.smartdock.profile.engine.ProfileScoreSnapshot> selectLatestSnapshots(
        @Param("tenantId") String tenantId, @Param("source") String source);
}
