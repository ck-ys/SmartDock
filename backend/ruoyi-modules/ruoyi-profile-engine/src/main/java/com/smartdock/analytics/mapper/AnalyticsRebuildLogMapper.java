package com.smartdock.analytics.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.smartdock.analytics.domain.AnalyticsRebuildLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 重建留痕 Mapper(批10 Q5)。
 *
 * @author smartdock
 */
@Mapper
public interface AnalyticsRebuildLogMapper extends BaseMapperPlus<AnalyticsRebuildLog, AnalyticsRebuildLog> {

    /** 近 N 次重建耗时(健康页趋势图原料)。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT duration_ms AS "durationMs", sessions, kind, finished_at AS "finishedAt"
        FROM analytics_rebuild_log
        WHERE tenant_id = #{tenantId} AND source = #{source}
        ORDER BY finished_at DESC
        LIMIT #{limit}
        """)
    List<Map<String, Object>> selectRecent(@Param("tenantId") String tenantId,
                                           @Param("source") String source,
                                           @Param("limit") int limit);

    /** 重建耗时分位数(P50/P95), 越界源返回空。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms) AS "p50",
               percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS "p95",
               count(*) AS "times"
        FROM analytics_rebuild_log
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND finished_at >= #{from}
        """)
    Map<String, Object> selectDurationPercentiles(@Param("tenantId") String tenantId,
                                                   @Param("source") String source,
                                                   @Param("from") Date from);

    /** 上次成功重建的完成时间(算积压: 此后写入的事件都还算"未被重建消化")。
     * 三审 P2: 排除 BACKFILL——每日兜底只补昨日一天, 若计入会掩盖 RECENT 链停摆的真积压。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT max(finished_at) FROM analytics_rebuild_log
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND kind <> 'BACKFILL'
        """)
    Date selectLastFinishedAt(@Param("tenantId") String tenantId, @Param("source") String source);

    /**
     * 双实例去重的水位: 最近一次 RECENT 重建实际读到的事件落库时间上限。
     * 比较对象是"读取时刻"而非"完成时刻"——重建执行期间写入的事件不会被误判为已覆盖。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT read_watermark FROM analytics_rebuild_log
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND kind = 'RECENT' AND read_watermark IS NOT NULL
        ORDER BY finished_at DESC
        LIMIT 1
        """)
    Date selectLastReadWatermark(@Param("tenantId") String tenantId, @Param("source") String source);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
        DELETE FROM analytics_rebuild_log
        WHERE finished_at < #{before}
        """)
    int deleteBefore(@Param("before") Date before);
}
