package com.smartdock.profile.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.profile.domain.ProfileReviewQueue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 低分复核队列 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileReviewQueueMapper extends BaseMapperPlus<ProfileReviewQueue, ProfileReviewQueue> {

    /**
     * 批10 R5: 超时置顶改为 SQL 级排序——此前 Java 侧只对"当前页"排, 跨页顺序不保证。
     * SLA 随源配置走 LEFT JOIN, 无配置默认 7 天; 超时 = PENDING 且入队时间+SLA 已过。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        <script>
        SELECT r.* FROM profile_review_queue r
        LEFT JOIN profile_engine_config c
          ON c.tenant_id = r.tenant_id AND c.source = r.source AND c.del_flag = '0'
        WHERE r.tenant_id = #{tenantId} AND r.del_flag = '0'
          <if test="source != null and source != ''">AND r.source = #{source}</if>
          <if test="status != null and status != ''">AND r.status = #{status}</if>
          <if test="riskLevel != null and riskLevel != ''">AND r.risk_level = #{riskLevel}</if>
          <if test="keyword != null and keyword != ''">
            AND (r.subject_id LIKE CONCAT('%', #{keyword}, '%')
                 OR r.display_name LIKE CONCAT('%', #{keyword}, '%'))
          </if>
        ORDER BY (CASE WHEN r.status = 'PENDING'
                           AND r.create_time + (COALESCE(c.review_sla_days, 7) || ' days')::interval &lt; now()
                      THEN 0 ELSE 1 END) ASC,
                 r.create_time DESC
        </script>
        """)
    IPage<ProfileReviewQueue> selectPageOverdueFirst(Page<ProfileReviewQueue> page,
                                                      @Param("tenantId") String tenantId,
                                                      @Param("source") String source,
                                                      @Param("status") String status,
                                                      @Param("riskLevel") String riskLevel,
                                                      @Param("keyword") String keyword);

    /**
     * B 片三审回修: 自动销案改为条件更新——只对仍处 PENDING 的行生效,
     * 与人工确认(同毫秒窗口)并发时后写不再覆盖先写(CONFIRMED 不会被冲成 SUPERSEDED)。
     */
    @Update("""
        UPDATE profile_review_queue
           SET status = 'SUPERSEDED', review_note = #{note}, reviewed_at = #{now},
               update_time = CURRENT_TIMESTAMP
         WHERE review_id = #{reviewId} AND tenant_id = #{tenantId}
           AND status = 'PENDING' AND del_flag = '0'
        """)
    int supersedeIfPending(@Param("reviewId") Long reviewId,
                           @Param("tenantId") String tenantId,
                           @Param("note") String note,
                           @Param("now") java.util.Date now);
}
