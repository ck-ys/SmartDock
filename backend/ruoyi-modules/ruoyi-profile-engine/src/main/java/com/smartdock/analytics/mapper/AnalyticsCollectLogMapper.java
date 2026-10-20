package com.smartdock.analytics.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.smartdock.analytics.domain.AnalyticsCollectLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 采集丢弃留痕查询：自定义 SQL 自带租户条件，禁用租户行插件注入。
 *
 * @author smartdock
 */
@Mapper
public interface AnalyticsCollectLogMapper extends BaseMapperPlus<AnalyticsCollectLog, AnalyticsCollectLog> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT drop_reason AS "reason",
               count(*) AS "batches",
               coalesce(sum(event_count), 0) AS "events"
        FROM analytics_collect_log
        WHERE tenant_id = #{tenantId} AND source = #{source} AND create_time >= #{since}
        GROUP BY drop_reason
        ORDER BY "events" DESC
        """)
    List<Map<String, Object>> summaryByReason(@Param("tenantId") String tenantId,
                                              @Param("source") String source,
                                              @Param("since") Date since);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT user_agent AS "userAgent",
               count(*) AS "batches",
               coalesce(sum(event_count), 0) AS "events",
               max(create_time) AS "lastSeen"
        FROM analytics_collect_log
        WHERE tenant_id = #{tenantId} AND source = #{source} AND create_time >= #{since}
          AND drop_reason = 'robot'
        GROUP BY user_agent
        ORDER BY "events" DESC
        LIMIT #{limit}
        """)
    List<Map<String, Object>> topRobots(@Param("tenantId") String tenantId,
                                        @Param("source") String source,
                                        @Param("since") Date since,
                                        @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT origin_host AS "originHost",
               count(*) AS "batches",
               coalesce(sum(event_count), 0) AS "events",
               max(create_time) AS "lastSeen"
        FROM analytics_collect_log
        WHERE tenant_id = #{tenantId} AND source = #{source} AND create_time >= #{since}
          AND drop_reason = 'domain_mismatch'
        GROUP BY origin_host
        ORDER BY "events" DESC
        LIMIT #{limit}
        """)
    List<Map<String, Object>> topMismatches(@Param("tenantId") String tenantId,
                                            @Param("source") String source,
                                            @Param("since") Date since,
                                            @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT drop_reason AS "reason", event_count AS "eventCount", user_agent AS "userAgent",
               origin_host AS "originHost", detail, create_time AS "createTime"
        FROM analytics_collect_log
        WHERE tenant_id = #{tenantId} AND source = #{source} AND create_time >= #{since}
        ORDER BY create_time DESC
        LIMIT #{limit}
        """)
    List<Map<String, Object>> latestLogs(@Param("tenantId") String tenantId,
                                         @Param("source") String source,
                                         @Param("since") Date since,
                                         @Param("limit") int limit);

    /**
     * 保留期清理（跨租户，调用方须 TenantHelper.ignore），单轮限量。
     *
     * @return 删除行数
     */
    @Delete("""
        DELETE FROM analytics_collect_log WHERE log_id IN (
            SELECT log_id FROM analytics_collect_log WHERE create_time < #{before} LIMIT #{limit}
        )
        """)
    int deleteBefore(@Param("before") Date before, @Param("limit") int limit);
}
