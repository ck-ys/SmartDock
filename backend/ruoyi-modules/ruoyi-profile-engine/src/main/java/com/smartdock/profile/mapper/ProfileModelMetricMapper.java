package com.smartdock.profile.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 评分模型指标聚合: 一次 SQL 取全量主体的指标分布。
 *
 * <p>subjectType 为空 = 不限定主体类型(模型全量运行); 有值 = 只算该类型。
 * 返回列固定为 subject_type / subject_id / metric_value, 由服务层补齐缺失主体。
 * props 键已由 ProfileModelConfig 做白名单校验, 这里只做绑定参数。</p>
 *
 * @author smartdock
 */
@Mapper
public interface ProfileModelMetricMapper {

    String EVENT_FILTER = """
        WHERE tenant_id = #{tenantId} AND source = #{source}
        <if test="subjectType != null and subjectType != ''">
          AND subject_type = #{subjectType}
        </if>
          AND del_flag = '0'
        <if test="eventTypes != null and eventTypes.size() > 0">
          AND event_type IN
          <foreach collection="eventTypes" item="et" open="(" separator="," close=")">#{et}</foreach>
        </if>
        """;

    @Select("""
        <script>
        SELECT subject_type, subject_id,
               GREATEST(0, extract(epoch FROM (now() - max(occurred_time))) / 86400.0) AS metric_value
        FROM profile_event
        """ + EVENT_FILTER + """
        GROUP BY subject_type, subject_id
        </script>
        """)
    List<Map<String, Object>> selectRecencyDays(@Param("tenantId") String tenantId,
                                                @Param("source") String source,
                                                @Param("subjectType") String subjectType,
                                                @Param("eventTypes") List<String> eventTypes);

    @Select("""
        <script>
        SELECT subject_type, subject_id, count(*)::numeric AS metric_value
        FROM profile_event
        """ + EVENT_FILTER + """
        GROUP BY subject_type, subject_id
        </script>
        """)
    List<Map<String, Object>> selectCount(@Param("tenantId") String tenantId,
                                          @Param("source") String source,
                                          @Param("subjectType") String subjectType,
                                          @Param("eventTypes") List<String> eventTypes);

    @Select("""
        <script>
        SELECT subject_type, subject_id,
               count(DISTINCT nullif(props ->> #{prop}, ''))::numeric AS metric_value
        FROM profile_event
        """ + EVENT_FILTER + """
        GROUP BY subject_type, subject_id
        </script>
        """)
    List<Map<String, Object>> selectCountDistinct(@Param("tenantId") String tenantId,
                                                  @Param("source") String source,
                                                  @Param("subjectType") String subjectType,
                                                  @Param("eventTypes") List<String> eventTypes,
                                                  @Param("prop") String prop);

    @Select("""
        <script>
        SELECT subject_type, subject_id,
               sum(CASE WHEN (props ->> #{prop}) ~ '^-?[0-9]+(\\.[0-9]+)?$'
                        THEN (props ->> #{prop})::numeric END) AS metric_value
        FROM profile_event
        """ + EVENT_FILTER + """
        GROUP BY subject_type, subject_id
        </script>
        """)
    List<Map<String, Object>> selectSum(@Param("tenantId") String tenantId,
                                        @Param("source") String source,
                                        @Param("subjectType") String subjectType,
                                        @Param("eventTypes") List<String> eventTypes,
                                        @Param("prop") String prop);

    @Select("""
        <script>
        SELECT subject_type, subject_id,
               avg(CASE WHEN (props ->> #{prop}) ~ '^-?[0-9]+(\\.[0-9]+)?$'
                        THEN (props ->> #{prop})::numeric END) AS metric_value
        FROM profile_event
        """ + EVENT_FILTER + """
        GROUP BY subject_type, subject_id
        </script>
        """)
    List<Map<String, Object>> selectAvg(@Param("tenantId") String tenantId,
                                        @Param("source") String source,
                                        @Param("subjectType") String subjectType,
                                        @Param("eventTypes") List<String> eventTypes,
                                        @Param("prop") String prop);

    @Select("""
        <script>
        SELECT DISTINCT ON (subject_type, subject_id) subject_type, subject_id,
               CASE WHEN (props ->> #{prop}) ~ '^-?[0-9]+(\\.[0-9]+)?$'
                    THEN (props ->> #{prop})::numeric END AS metric_value
        FROM profile_event
        """ + EVENT_FILTER + """
        ORDER BY subject_type, subject_id, occurred_time DESC
        </script>
        """)
    List<Map<String, Object>> selectLastValue(@Param("tenantId") String tenantId,
                                              @Param("source") String source,
                                              @Param("subjectType") String subjectType,
                                              @Param("eventTypes") List<String> eventTypes,
                                              @Param("prop") String prop);

    @Select("""
        <script>
        SELECT n.subject_type, n.subject_id,
               (n.cnt::numeric / d.cnt) AS metric_value
        FROM (
            SELECT subject_type, subject_id, count(*) AS cnt
            FROM profile_event
            WHERE tenant_id = #{tenantId} AND source = #{source}
              AND del_flag = '0' AND event_type = #{numerator}
            <if test="subjectType != null and subjectType != ''">AND subject_type = #{subjectType}</if>
            GROUP BY subject_type, subject_id
        ) n
        JOIN (
            SELECT subject_type, subject_id, count(*) AS cnt
            FROM profile_event
            WHERE tenant_id = #{tenantId} AND source = #{source}
              AND del_flag = '0' AND event_type = #{denominator}
            <if test="subjectType != null and subjectType != ''">AND subject_type = #{subjectType}</if>
            GROUP BY subject_type, subject_id
        ) d ON d.subject_type = n.subject_type AND d.subject_id = n.subject_id AND d.cnt > 0
        </script>
        """)
    List<Map<String, Object>> selectRate(@Param("tenantId") String tenantId,
                                         @Param("source") String source,
                                         @Param("subjectType") String subjectType,
                                         @Param("numerator") String numerator,
                                         @Param("denominator") String denominator);
}
