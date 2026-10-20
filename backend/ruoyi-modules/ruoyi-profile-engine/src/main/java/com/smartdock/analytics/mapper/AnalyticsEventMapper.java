package com.smartdock.analytics.mapper;

import com.smartdock.analytics.query.AnalyticsBreakdownRow;
import com.smartdock.analytics.query.AnalyticsEventRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 分析中心的事件投影（只读）。
 *
 * @author smartdock
 */
@Mapper
public interface AnalyticsEventMapper {

    @Select("""
        SELECT event_id AS "eventId", event_name AS "eventName", subject_type AS "subjectType",
               subject_id AS "subjectId", anonymous_id AS "anonymousId", session_id AS "sessionId",
               COALESCE(NULLIF(anonymous_id, ''), subject_id) AS "visitorKey",
               occurred_time AS "occurredTime", props::text AS props, context::text AS context
        FROM profile_event
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND occurred_time >= #{from} AND occurred_time < #{to}
          AND del_flag = '0'
        ORDER BY occurred_time, event_id
        """)
    List<AnalyticsEventRow> selectEvents(@Param("tenantId") String tenantId,
                                         @Param("source") String source,
                                         @Param("from") Date from,
                                         @Param("to") Date to);

    /**
     * 每个访问者在业务源内的首次出现时间（判断新/老访客）。
     */
    @Select("""
        SELECT COALESCE(NULLIF(anonymous_id, ''), subject_id) AS "visitorKey",
               min(occurred_time) AS "firstSeen"
        FROM profile_event
        WHERE tenant_id = #{tenantId} AND source = #{source} AND del_flag = '0'
        GROUP BY 1
        """)
    List<Map<String, Object>> selectVisitorFirstSeen(@Param("tenantId") String tenantId,
                                                     @Param("source") String source);

    @Select("""
        <script>
        SELECT label, pv, uv, visits, events FROM (
            SELECT COALESCE(NULLIF(context->>'pageUrl', ''), NULLIF(context->>'url', ''),
                            NULLIF(props->>'pageUrl', ''), '(未知页面)') AS label,
                   count(*) FILTER (WHERE event_name IN ('page_view', 'pageview', 'pv')) AS pv,
                   count(DISTINCT COALESCE(NULLIF(anonymous_id, ''), subject_id)) AS uv,
                   count(DISTINCT COALESCE(session_id, NULLIF(anonymous_id, ''), subject_id)) AS visits,
                   count(*) AS events
            FROM profile_event
            WHERE tenant_id = #{tenantId} AND source = #{source}
              AND occurred_time >= #{from} AND occurred_time &lt; #{to} AND del_flag = '0'
              AND event_name IN ('page_view', 'pageview', 'pv')
            GROUP BY 1
        ) t
        ORDER BY pv DESC, events DESC
        LIMIT #{limit}
        </script>
        """)
    List<AnalyticsBreakdownRow> selectPageBreakdown(@Param("tenantId") String tenantId,
                                                    @Param("source") String source,
                                                    @Param("from") Date from,
                                                    @Param("to") Date to,
                                                    @Param("limit") int limit);

    @Select("""
        SELECT label, count(*) AS pv,
               count(DISTINCT visitor_key) AS uv,
               count(DISTINCT COALESCE(session_id, visitor_key)) AS visits,
               count(*) AS events
        FROM (
            SELECT COALESCE(NULLIF(context->>'searchTerm', ''), NULLIF(props->>'keyword', ''),
                            NULLIF(context->>'keyword', '')) AS label,
                   COALESCE(NULLIF(anonymous_id, ''), subject_id) AS visitor_key,
                   session_id
            FROM profile_event
            WHERE tenant_id = #{tenantId} AND source = #{source}
              AND occurred_time >= #{from} AND occurred_time < #{to} AND del_flag = '0'
              AND (NULLIF(context->>'searchTerm', '') IS NOT NULL
                   OR NULLIF(props->>'keyword', '') IS NOT NULL
                   OR NULLIF(context->>'keyword', '') IS NOT NULL)
        ) t
        WHERE label IS NOT NULL
        GROUP BY label
        ORDER BY pv DESC
        LIMIT #{limit}
        """)
    List<AnalyticsBreakdownRow> selectSearchBreakdown(@Param("tenantId") String tenantId,
                                                      @Param("source") String source,
                                                      @Param("from") Date from,
                                                      @Param("to") Date to,
                                                      @Param("limit") int limit);

    @Select("""
        SELECT event_name AS label, count(*) AS pv,
               count(DISTINCT COALESCE(NULLIF(anonymous_id, ''), subject_id)) AS uv,
               count(DISTINCT COALESCE(session_id, NULLIF(anonymous_id, ''), subject_id)) AS visits,
               count(*) AS events
        FROM profile_event
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND occurred_time >= #{from} AND occurred_time < #{to} AND del_flag = '0'
        GROUP BY event_name
        ORDER BY events DESC
        LIMIT #{limit}
        """)
    List<AnalyticsBreakdownRow> selectEventBreakdown(@Param("tenantId") String tenantId,
                                                     @Param("source") String source,
                                                     @Param("from") Date from,
                                                     @Param("to") Date to,
                                                     @Param("limit") int limit);

    /**
     * 单个访问者的事件（隐式会话时间线）。
     *
     * <p>批8 串台修复：只按 visitorKey 严格匹配。隐式会话本身按 visitorKey 分组构建，
     * 本会话事件必然同键命中；此前的 {@code OR subject_id = #{visitorKey}} 会把
     * "同一登录主体在另一端/另一次访问"的无会话事件混进来（一期审查遗留串台 bug）。
     * 登录前后跨键的完整时间线由显式会话走 selectSessionEvents（按 sessionId）。</p>
     */
    @Select("""
        SELECT event_id AS "eventId", event_name AS "eventName", subject_type AS "subjectType",
               subject_id AS "subjectId", anonymous_id AS "anonymousId", session_id AS "sessionId",
               COALESCE(NULLIF(anonymous_id, ''), subject_id) AS "visitorKey",
               occurred_time AS "occurredTime", props::text AS props, context::text AS context
        FROM profile_event
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND COALESCE(NULLIF(anonymous_id, ''), subject_id) = #{visitorKey}
          AND occurred_time >= #{from} AND occurred_time < #{to}
          AND del_flag = '0'
        ORDER BY occurred_time, event_id
        """)
    List<AnalyticsEventRow> selectVisitorEvents(@Param("tenantId") String tenantId,
                                                @Param("source") String source,
                                                @Param("visitorKey") String visitorKey,
                                                @Param("from") Date from,
                                                @Param("to") Date to);

    /**
     * 实时看板（批8）：按分钟的浏览/事件/访客桶。
     */
    @Select("""
        SELECT to_char(date_trunc('minute', occurred_time), 'YYYY-MM-DD HH24:MI') AS "minute",
               count(*) AS "events",
               count(*) FILTER (WHERE event_name IN ('page_view', 'pageview', 'pv')) AS "pv",
               count(DISTINCT COALESCE(NULLIF(anonymous_id, ''), subject_id)) AS "visitors"
        FROM profile_event
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND occurred_time >= #{from} AND occurred_time < #{to} AND del_flag = '0'
        GROUP BY 1
        ORDER BY 1
        """)
    List<Map<String, Object>> selectRealtimeBuckets(@Param("tenantId") String tenantId,
                                                    @Param("source") String source,
                                                    @Param("from") Date from,
                                                    @Param("to") Date to);

    /**
     * 实时看板（批8）：窗口内去重访客键列表（跨分钟去重在 Java 侧 size 完成）。
     */
    @Select("""
        SELECT DISTINCT COALESCE(NULLIF(anonymous_id, ''), subject_id) AS "visitorKey"
        FROM profile_event
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND occurred_time >= #{from} AND occurred_time < #{to} AND del_flag = '0'
        """)
    List<Map<String, Object>> selectWindowVisitors(@Param("tenantId") String tenantId,
                                                    @Param("source") String source,
                                                    @Param("from") Date from,
                                                    @Param("to") Date to);

    /**
     * 活跃口径（批8）：天 × 访客 去重对（DAU/WAU/MAU 滚动窗口的原料）。
     */
    @Select("""
        SELECT DISTINCT to_char(date_trunc('day', occurred_time), 'YYYY-MM-DD') AS "day",
               COALESCE(NULLIF(anonymous_id, ''), subject_id) AS "visitorKey"
        FROM profile_event
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND occurred_time >= #{from} AND occurred_time < #{to} AND del_flag = '0'
        ORDER BY 1
        """)
    List<Map<String, Object>> selectDailyActiveVisitors(@Param("tenantId") String tenantId,
                                                        @Param("source") String source,
                                                        @Param("from") Date from,
                                                        @Param("to") Date to);

    /**
     * 全域总览（批8）：跨业务源的事件量/访客数/最近写入（只读事件账本，不依赖重建）。
     */
    @Select("""
        SELECT source,
               count(*) AS "events",
               count(DISTINCT COALESCE(NULLIF(anonymous_id, ''), subject_id)) AS "visitors",
               max(occurred_time) AS "lastAt"
        FROM profile_event
        WHERE tenant_id = #{tenantId} AND occurred_time >= #{from} AND del_flag = '0'
        GROUP BY source
        ORDER BY "events" DESC
        """)
    List<Map<String, Object>> selectCrossSourceSummary(@Param("tenantId") String tenantId,
                                                       @Param("from") Date from);

    /**
     * 显式会话的事件: 按 sessionId 查, 登录前后(匿名 ID → 用户 ID)的事件都能取到。
     */
    @Select("""
        SELECT event_id AS "eventId", event_name AS "eventName", subject_type AS "subjectType",
               subject_id AS "subjectId", anonymous_id AS "anonymousId", session_id AS "sessionId",
               COALESCE(NULLIF(anonymous_id, ''), subject_id) AS "visitorKey",
               occurred_time AS "occurredTime", props::text AS props, context::text AS context
        FROM profile_event
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND session_id = #{sessionId}
          AND occurred_time >= #{from} AND occurred_time < #{to}
          AND del_flag = '0'
        ORDER BY occurred_time, event_id
        """)
    List<AnalyticsEventRow> selectSessionEvents(@Param("tenantId") String tenantId,
                                                @Param("source") String source,
                                                @Param("sessionId") String sessionId,
                                                @Param("from") Date from,
                                                @Param("to") Date to);
}
