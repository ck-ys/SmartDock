package org.dromara.system.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 公告已读 Mapper（N3 2026-10-23）。
 *
 * <p>联合主键（公告 + 用户），重复标记走 ON CONFLICT DO NOTHING。所有 SQL 自带
 * tenant_id，不依赖租户插件（Mapper 接口无实体，插件不会介入）。</p>
 *
 * @author smartdock
 */
@Mapper
public interface SysNoticeReadMapper {

    @Insert("""
        INSERT INTO sys_notice_read (notice_id, user_id, tenant_id, read_time)
        VALUES (#{noticeId}, #{userId}, #{tenantId}, now())
        ON CONFLICT (notice_id, user_id) DO NOTHING
        """)
    int markRead(@Param("noticeId") Long noticeId, @Param("userId") Long userId,
                 @Param("tenantId") String tenantId);

    @Select("""
        <script>
        SELECT notice_id FROM sys_notice_read
        WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND notice_id IN
        <foreach collection="noticeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<Long> selectReadIds(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                             @Param("noticeIds") List<Long> noticeIds);

    /**
     * 未读公告数：已发布、未过期、且本用户没有已读记录。
     *
     * <p>时间基准必须由调用方传入（与列表同源）：应用与数据库时钟可能有秒级偏差，
     * 一边用 Java 的 new Date()、一边用库里的 now() 会让刚发布的公告“列表可见但角标不计数”。</p>
     */
    @Select("""
        SELECT count(*) FROM sys_notice n
        WHERE n.tenant_id = #{tenantId}
          AND n.status = '0'
          AND (n.publish_time IS NULL OR n.publish_time <= #{now})
          AND (n.expire_time IS NULL OR n.expire_time > #{now})
          AND NOT EXISTS (
              SELECT 1 FROM sys_notice_read r
              WHERE r.notice_id = n.notice_id AND r.user_id = #{userId}
          )
        """)
    long countUnread(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                     @Param("now") java.util.Date now);
}
