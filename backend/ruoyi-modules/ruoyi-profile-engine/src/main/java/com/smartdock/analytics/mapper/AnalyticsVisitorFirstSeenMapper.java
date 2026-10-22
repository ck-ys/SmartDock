package com.smartdock.analytics.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 访客首见物化表 analytics_visitor_first_seen(批10 Q7)。
 *
 * <p>键口径与原全表聚合 SQL 完全一致: COALESCE(NULLIF(anonymous_id,''), subject_id)。
 * 事件写入时增量维护(只降不升), 重建/实时看板直查——消灭每次重建的全表聚合。</p>
 *
 * @author smartdock
 */
@Mapper
public interface AnalyticsVisitorFirstSeenMapper {

    /**
     * 增量维护: 迟到首见事件取 LEAST 只降不升(拍板 C.7, 须与 rebuildRecent 窗口修复联动)。
     */
    @Insert("""
        INSERT INTO analytics_visitor_first_seen (tenant_id, source, visitor_key, first_seen)
        VALUES (#{tenantId}, #{source}, #{visitorKey}, #{firstSeen})
        ON CONFLICT (tenant_id, source, visitor_key)
        DO UPDATE SET first_seen = LEAST(analytics_visitor_first_seen.first_seen, EXCLUDED.first_seen)
        """)
    int upsert(@Param("tenantId") String tenantId,
               @Param("source") String source,
               @Param("visitorKey") String visitorKey,
               @Param("firstSeen") Date firstSeen);

    @Select("""
        SELECT visitor_key AS "visitorKey", first_seen AS "firstSeen"
        FROM analytics_visitor_first_seen
        WHERE tenant_id = #{tenantId} AND source = #{source}
        """)
    List<Map<String, Object>> selectAll(@Param("tenantId") String tenantId,
                                        @Param("source") String source);

    /** 实时看板新访客: 只取窗口内首见的行, 避免全表行进 Java 再过滤。 */
    @Select("""
        SELECT count(*) FROM analytics_visitor_first_seen
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND first_seen >= #{since}
        """)
    long countSince(@Param("tenantId") String tenantId,
                    @Param("source") String source,
                    @Param("since") Date since);

    /** 数据接入页「清掉模拟数据」: 按前缀删模拟访客首见行, 与事件一起清不留幽灵新访客。 */
    @Delete("""
        DELETE FROM analytics_visitor_first_seen
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND visitor_key LIKE #{prefix} || '%'
        """)
    int deleteByVisitorKeyPrefix(@Param("tenantId") String tenantId,
                                 @Param("source") String source,
                                 @Param("prefix") String prefix);
}
