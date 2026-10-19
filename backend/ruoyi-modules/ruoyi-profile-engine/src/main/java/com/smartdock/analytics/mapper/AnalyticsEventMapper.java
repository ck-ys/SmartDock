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
     * 单个访问者在时间窗内的事件（会话时间线）。
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
}
