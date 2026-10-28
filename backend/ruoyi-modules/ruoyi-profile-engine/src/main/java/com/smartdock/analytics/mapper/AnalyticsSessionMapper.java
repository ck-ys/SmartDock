package com.smartdock.analytics.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.analytics.domain.AnalyticsSession;
import com.smartdock.analytics.query.AnalyticsBreakdownRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 会话表查询。
 *
 * @author smartdock
 */
@Mapper
public interface AnalyticsSessionMapper extends BaseMapperPlus<AnalyticsSession, AnalyticsSession> {

    @Delete("""
        DELETE FROM analytics_session
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND started_at >= #{from} AND started_at < #{to}
        """)
    int deleteRange(@Param("tenantId") String tenantId,
                    @Param("source") String source,
                    @Param("from") Date from,
                    @Param("to") Date to);

    /**
     * R2-5 项11: 事件过保留期出账后同步清对应窗口会话——
     * 保留期边界起, 总览/趋势(读 session/daily)与漏斗/路径(直查事件)两套数字不再分叉。
     */
    @Delete("""
        DELETE FROM analytics_session
        WHERE tenant_id = #{tenantId} AND source = #{source} AND started_at < #{watermark}
        """)
    int deleteBeforeWatermark(@Param("tenantId") String tenantId,
                              @Param("source") String source,
                              @Param("watermark") Date watermark);

    /** 回放对账(批10 Q3): 窗口内会话数/浏览量/跳出数。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT count(*) AS "sessions",
               coalesce(sum(page_views), 0) AS "pageViews",
               count(*) FILTER (WHERE is_bounce = '1') AS "bounces"
        FROM analytics_session
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND started_at >= #{from} AND started_at < #{to}
        """)
    Map<String, Object> selectReplayStats(@Param("tenantId") String tenantId,
                                          @Param("source") String source,
                                          @Param("from") Date from,
                                          @Param("to") Date to);

    /**
     * 按 session_key 精确清理: 重建时起点在窗口外的边界会话 deleteRange 删不到, 不清会撞唯一键。
     */
    @Delete("""
        <script>
        DELETE FROM analytics_session
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND session_key IN
          <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
        </script>
        """)
    int deleteByKeys(@Param("tenantId") String tenantId,
                     @Param("source") String source,
                     @Param("keys") List<String> keys);

    @Select("""
        SELECT count(*) AS "visits",
               count(DISTINCT visitor_id) AS "uv",
               coalesce(sum(page_views), 0) AS "pv",
               coalesce(sum(event_count), 0) AS "events",
               coalesce(sum(CASE WHEN is_new_visitor = '1' THEN 1 ELSE 0 END), 0) AS "newVisitors",
               coalesce(sum(CASE WHEN is_new_visitor = '0' THEN 1 ELSE 0 END), 0) AS "returningVisitors",
               coalesce(sum(CASE WHEN is_bounce = '1' THEN 1 ELSE 0 END), 0) AS "bounces",
               coalesce(sum(duration_seconds), 0) AS "durationSeconds"
        FROM analytics_session
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND started_at >= #{from} AND started_at < #{to} AND del_flag = '0'
        """)
    Map<String, Object> selectOverview(@Param("tenantId") String tenantId,
                                       @Param("source") String source,
                                       @Param("from") Date from,
                                       @Param("to") Date to);

    @Select("""
        <script>
        SELECT * FROM analytics_session
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND started_at &gt;= #{from} AND started_at &lt; #{to}
          AND del_flag = '0'
          <if test="q.visitorId != null and q.visitorId != ''">
            AND visitor_id = #{q.visitorId}
          </if>
          <if test="q.subjectId != null and q.subjectId != ''">
            AND subject_id = #{q.subjectId}
          </if>
          <if test="q.channel != null and q.channel != ''">
            AND source_channel = #{q.channel}
          </if>
          <if test="q.deviceType != null and q.deviceType != ''">
            AND device_type = #{q.deviceType}
          </if>
          <if test="q.browser != null and q.browser != ''">
            AND browser = #{q.browser}
          </if>
          <if test="q.country != null and q.country != ''">
            AND country = #{q.country}
          </if>
          <if test="q.bounce != null and q.bounce != ''">
            AND is_bounce = #{q.bounce}
          </if>
          <if test="q.keyword != null and q.keyword != ''">
            AND (visitor_id LIKE concat('%', #{q.keyword}, '%')
                 OR subject_id LIKE concat('%', #{q.keyword}, '%')
                 OR entry_page LIKE concat('%', #{q.keyword}, '%'))
          </if>
        ORDER BY started_at DESC, session_id DESC
        </script>
        """)
    IPage<AnalyticsSession> selectSessionPage(Page<AnalyticsSession> page,
                                              @Param("tenantId") String tenantId,
                                              @Param("source") String source,
                                              @Param("from") Date from,
                                              @Param("to") Date to,
                                              @Param("q") com.smartdock.analytics.query.AnalyticsSessionQuery query);

    /**
     * 会话级维度下钻。column 由 Service 白名单映射，不接受外部原样输入。
     */
    @Select("""
        SELECT coalesce(nullif(${column}, ''), '(未知)') AS label,
               coalesce(sum(page_views), 0) AS pv,
               count(DISTINCT visitor_id) AS uv,
               count(*) AS visits,
               coalesce(sum(event_count), 0) AS events
        FROM analytics_session
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND started_at >= #{from} AND started_at < #{to} AND del_flag = '0'
        GROUP BY 1
        ORDER BY pv DESC, events DESC
        LIMIT #{limit}
        """)
    List<AnalyticsBreakdownRow> selectBreakdown(@Param("tenantId") String tenantId,
                                                @Param("source") String source,
                                                @Param("from") Date from,
                                                @Param("to") Date to,
                                                @Param("column") String column,
                                                @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        WITH first_seen AS (
            SELECT visitor_id, date_trunc(#{granularity}, min(started_at))::date AS cohort
            FROM analytics_session
            WHERE tenant_id = #{tenantId} AND source = #{source} AND del_flag = '0'
            GROUP BY visitor_id
        )
        SELECT f.cohort AS "cohortDate", s.visitor_id AS "visitorId",
               date_trunc(#{granularity}, s.started_at)::date AS "periodDate"
        FROM analytics_session s
        JOIN first_seen f ON f.visitor_id = s.visitor_id
        WHERE s.tenant_id = #{tenantId} AND s.source = #{source} AND s.del_flag = '0'
          -- 三审 P2(dsflash): from 先按粒度截断再比较——from 是任意日期, 周/月粒度下
          -- 首个不完整周期的 cohort(如 days=30 从 09-27 起, 9 月首访者 cohort=09-01)
          -- 若不截断会被 >= from 整段排除, 丢掉一批真实首访者
          AND f.cohort >= date_trunc(#{granularity}, #{from}::date) AND f.cohort <= #{to}::date
          AND s.started_at::date <= #{to}::date
        GROUP BY 1, 2, 3
        ORDER BY 1, 3
        """)
    List<Map<String, Object>> selectRetention(@Param("tenantId") String tenantId,
                                              @Param("source") String source,
                                              @Param("from") Date from,
                                              @Param("to") Date to,
                                              @Param("granularity") String granularity);
}
