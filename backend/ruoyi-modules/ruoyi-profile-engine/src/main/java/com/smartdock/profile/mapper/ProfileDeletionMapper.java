package com.smartdock.profile.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.smartdock.profile.domain.ProfileDeletionLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.Date;
import java.util.List;

/**
 * 主体级删除向导的落库+计数+清理 SQL(批11 Q2)。
 *
 * <p>删除口径: 主体本人行(subject_id 匹配) + 其匿名期事件(anonymous_id IN 访客键集合)。
 * 匿名期事件行 subject_id 为空, 只能靠当前身份链接归属; 共享浏览器的边界在预览页向操作者披露。
 * 手写 SQL 全部 @InterceptorIgnore + 自带租户条件(模块铁律)。</p>
 *
 * @author smartdock
 */
@Mapper
public interface ProfileDeletionMapper extends BaseMapperPlus<ProfileDeletionLog, ProfileDeletionLog> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT * FROM profile_deletion_log
        WHERE tenant_id = #{tenantId}
        ORDER BY create_time DESC
        LIMIT 50
        """)
    List<ProfileDeletionLog> selectRecent(@Param("tenantId") String tenantId);

    /**
     * 该主体的访客键集合: 主体自身 + 名下全部匿名标识(不限链接状态, 预览/日志披露)。
     * 事件/会话/首见的匿名期归属都按这个集合。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT DISTINCT identifier_value FROM profile_identity_link
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
          AND identifier_type = 'anonymous_id'
        UNION
        SELECT #{subjectId}
        UNION
        SELECT DISTINCT anonymous_id FROM profile_event
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
          AND anonymous_id IS NOT NULL AND anonymous_id <> ''
        """)
    List<String> selectVisitorKeys(@Param("tenantId") String tenantId, @Param("source") String source,
                                   @Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    // ------------------------------------------------------------ 计数(预览与执行共用口径)

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        <script>
        SELECT count(*) FROM profile_event
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND ((subject_type = #{subjectType} AND subject_id = #{subjectId})
            OR anonymous_id IN
            <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>)
        </script>
        """)
    long countEvents(@Param("tenantId") String tenantId, @Param("source") String source,
                     @Param("subjectType") String subjectType, @Param("subjectId") String subjectId,
                     @Param("keys") List<String> keys);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        <script>
        SELECT count(*) FROM profile_event_archive
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND ((subject_type = #{subjectType} AND subject_id = #{subjectId})
            OR anonymous_id IN
            <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>)
        </script>
        """)
    long countArchives(@Param("tenantId") String tenantId, @Param("source") String source,
                       @Param("subjectType") String subjectType, @Param("subjectId") String subjectId,
                       @Param("keys") List<String> keys);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        <script>
        SELECT count(*) FROM analytics_session
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND (visitor_id IN
            <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>
          OR (subject_type = #{subjectType} AND subject_id = #{subjectId}))
        </script>
        """)
    long countSessions(@Param("tenantId") String tenantId, @Param("source") String source,
                       @Param("subjectType") String subjectType, @Param("subjectId") String subjectId,
                       @Param("keys") List<String> keys);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT count(*) FROM profile_identity_link
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
        """)
    long countLinks(@Param("tenantId") String tenantId, @Param("source") String source,
                    @Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT (SELECT count(*) FROM profile_model_snapshot
          WHERE tenant_id = #{tenantId} AND source = #{source}
            AND subject_type = #{subjectType} AND subject_id = #{subjectId})
          + (SELECT count(*) FROM profile_score_snapshot
          WHERE tenant_id = #{tenantId} AND source = #{source}
            AND subject_type = #{subjectType} AND subject_id = #{subjectId})
        """)
    long countSnapshots(@Param("tenantId") String tenantId, @Param("source") String source,
                        @Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT count(*) FROM profile_segment_member
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
        """)
    long countMembers(@Param("tenantId") String tenantId, @Param("source") String source,
                      @Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT count(*) FROM profile_review_queue
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
        """)
    long countReviews(@Param("tenantId") String tenantId, @Param("source") String source,
                      @Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT count(*) FROM profile_subject
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
        """)
    long countSubjects(@Param("tenantId") String tenantId, @Param("source") String source,
                       @Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    // ------------------------------------------------------------ 事件时间范围(删除后重建窗口)

    /** 删除波及的最早/最晚事件时刻: 活表+归档、主体行+匿名期行一起取, 供重算窗口定界。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        <script>
        SELECT min(t) FROM (
          SELECT occurred_time AS t FROM profile_event
           WHERE tenant_id = #{tenantId} AND source = #{source}
             AND ((subject_type = #{subjectType} AND subject_id = #{subjectId})
               OR anonymous_id IN
               <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>)
          UNION ALL
          SELECT occurred_time FROM profile_event_archive
           WHERE tenant_id = #{tenantId} AND source = #{source}
             AND ((subject_type = #{subjectType} AND subject_id = #{subjectId})
               OR anonymous_id IN
               <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>)
        ) u
        </script>
        """)
    Date minOccurred(@Param("tenantId") String tenantId, @Param("source") String source,
                     @Param("subjectType") String subjectType, @Param("subjectId") String subjectId,
                     @Param("keys") List<String> keys);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        <script>
        SELECT max(t) FROM (
          SELECT occurred_time AS t FROM profile_event
           WHERE tenant_id = #{tenantId} AND source = #{source}
             AND ((subject_type = #{subjectType} AND subject_id = #{subjectId})
               OR anonymous_id IN
               <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>)
          UNION ALL
          SELECT occurred_time FROM profile_event_archive
           WHERE tenant_id = #{tenantId} AND source = #{source}
             AND ((subject_type = #{subjectType} AND subject_id = #{subjectId})
               OR anonymous_id IN
               <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>)
        ) u
        </script>
        """)
    Date maxOccurred(@Param("tenantId") String tenantId, @Param("source") String source,
                     @Param("subjectType") String subjectType, @Param("subjectId") String subjectId,
                     @Param("keys") List<String> keys);

    // ------------------------------------------------------------ 清理(同一删除事务内执行)

    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
        <script>
        DELETE FROM profile_event
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND ((subject_type = #{subjectType} AND subject_id = #{subjectId})
            OR anonymous_id IN
            <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>)
        </script>
        """)
    int deleteEvents(@Param("tenantId") String tenantId, @Param("source") String source,
                     @Param("subjectType") String subjectType, @Param("subjectId") String subjectId,
                     @Param("keys") List<String> keys);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
        <script>
        DELETE FROM profile_event_archive
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND ((subject_type = #{subjectType} AND subject_id = #{subjectId})
            OR anonymous_id IN
            <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>)
        </script>
        """)
    int deleteArchives(@Param("tenantId") String tenantId, @Param("source") String source,
                       @Param("subjectType") String subjectType, @Param("subjectId") String subjectId,
                       @Param("keys") List<String> keys);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
        <script>
        DELETE FROM analytics_session
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND (visitor_id IN
            <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>
          OR (subject_type = #{subjectType} AND subject_id = #{subjectId}))
        </script>
        """)
    int deleteSessions(@Param("tenantId") String tenantId, @Param("source") String source,
                       @Param("subjectType") String subjectType, @Param("subjectId") String subjectId,
                       @Param("keys") List<String> keys);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
        <script>
        DELETE FROM analytics_visitor_first_seen
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND visitor_key IN
            <foreach collection="keys" item="k" open="(" separator="," close=")">#{k}</foreach>
        </script>
        """)
    int deleteFirstSeen(@Param("tenantId") String tenantId, @Param("source") String source,
                        @Param("keys") List<String> keys);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
        DELETE FROM profile_identity_link
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
        """)
    int deleteLinks(@Param("tenantId") String tenantId, @Param("source") String source,
                    @Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
        DELETE FROM profile_model_snapshot
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
        """)
    int deleteSnapshots(@Param("tenantId") String tenantId, @Param("source") String source,
                        @Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    /** 批11 回修(终审 P1): 主评分快照链同样属主体数据, 删除一并清——否则删完人还能查到分数历史 */
    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
        DELETE FROM profile_score_snapshot
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
        """)
    int deleteScoreSnapshots(@Param("tenantId") String tenantId, @Param("source") String source,
                             @Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
        DELETE FROM profile_segment_member
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
        """)
    int deleteMembers(@Param("tenantId") String tenantId, @Param("source") String source,
                      @Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
        DELETE FROM profile_review_queue
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
        """)
    int deleteReviews(@Param("tenantId") String tenantId, @Param("source") String source,
                      @Param("subjectType") String subjectType, @Param("subjectId") String subjectId);

    @InterceptorIgnore(tenantLine = "true")
    @Delete("""
        DELETE FROM profile_subject
        WHERE tenant_id = #{tenantId} AND source = #{source}
          AND subject_type = #{subjectType} AND subject_id = #{subjectId}
        """)
    int deleteSubject(@Param("tenantId") String tenantId, @Param("source") String source,
                      @Param("subjectType") String subjectType, @Param("subjectId") String subjectId);
}
