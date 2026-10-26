package com.smartdock.analytics.mapper;

import com.smartdock.analytics.domain.AnalyticsDaily;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 日汇总表。
 *
 * @author smartdock
 */
@Mapper
public interface AnalyticsDailyMapper extends BaseMapperPlus<AnalyticsDaily, AnalyticsDaily> {

    @Delete("""
        DELETE FROM analytics_daily
        WHERE tenant_id = #{tenantId} AND source = #{source} AND stat_date BETWEEN #{from} AND #{to}
        """)
    int deleteRange(@Param("tenantId") String tenantId,
                    @Param("source") String source,
                    @Param("from") Date from,
                    @Param("to") Date to);

    /** R2-5 项11: 事件归档水位以下的日汇总行同步清理(口径与 session 水位一致)。 */
    @Delete("""
        DELETE FROM analytics_daily
        WHERE tenant_id = #{tenantId} AND source = #{source} AND stat_date < #{watermark}
        """)
    int deleteBeforeWatermark(@Param("tenantId") String tenantId,
                              @Param("source") String source,
                              @Param("watermark") Date watermark);

    @Insert("""
        INSERT INTO analytics_daily
            (daily_id, tenant_id, source, stat_date, pv, uv, visits, events,
             new_visitors, returning_visitors, bounces, duration_seconds, create_time)
        VALUES
            (#{d.dailyId}, #{d.tenantId}, #{d.source}, #{d.statDate}, #{d.pv}, #{d.uv}, #{d.visits},
             #{d.events}, #{d.newVisitors}, #{d.returningVisitors}, #{d.bounces},
             #{d.durationSeconds}, CURRENT_TIMESTAMP)
        ON CONFLICT (tenant_id, source, stat_date) DO UPDATE SET
            pv = EXCLUDED.pv,
            uv = EXCLUDED.uv,
            visits = EXCLUDED.visits,
            events = EXCLUDED.events,
            new_visitors = EXCLUDED.new_visitors,
            returning_visitors = EXCLUDED.returning_visitors,
            bounces = EXCLUDED.bounces,
            duration_seconds = EXCLUDED.duration_seconds,
            update_time = CURRENT_TIMESTAMP
        """)
    int upsert(@Param("d") AnalyticsDaily daily);

    @Select("""
        SELECT to_char(stat_date, 'YYYY-MM-DD') AS "statDate",
               pv, uv, visits, events,
               new_visitors AS "newVisitors",
               returning_visitors AS "returningVisitors",
               bounces, duration_seconds AS "durationSeconds"
        FROM analytics_daily
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND stat_date BETWEEN #{from} AND #{to}
        ORDER BY stat_date
        """)
    List<Map<String, Object>> selectTrend(@Param("tenantId") String tenantId,
                                          @Param("source") String source,
                                          @Param("from") Date from,
                                          @Param("to") Date to);
}
