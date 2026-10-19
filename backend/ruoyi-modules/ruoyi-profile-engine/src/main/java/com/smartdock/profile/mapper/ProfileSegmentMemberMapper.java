package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileSegmentMember;
import com.smartdock.profile.query.ProfileSegmentMemberRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

import java.util.List;

/**
 * 分群成员 Mapper。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileSegmentMemberMapper extends BaseMapperPlus<ProfileSegmentMember, ProfileSegmentMember> {

    @Select("""
        <script>
        SELECT m.member_id, m.run_id, m.subject_type, m.subject_id, m.display_name, m.reason, m.create_time,
               sub.event_count, sub.last_event_time, snap.total_score
        FROM profile_segment_member m
        LEFT JOIN profile_subject sub
          ON sub.tenant_id = m.tenant_id AND sub.source = m.source
         AND sub.subject_type = m.subject_type AND sub.subject_id = m.subject_id AND sub.del_flag = '0'
        LEFT JOIN LATERAL (
            SELECT total_score
            FROM profile_score_snapshot s
            WHERE s.tenant_id = m.tenant_id AND s.source = m.source
              AND s.subject_type = m.subject_type AND s.subject_id = m.subject_id
              AND s.del_flag = '0'
            ORDER BY s.calc_time DESC, s.snapshot_id DESC
            LIMIT 1
        ) snap ON TRUE
        WHERE m.tenant_id = #{tenantId} AND m.segment_id = #{segmentId} AND m.run_id = #{runId}
          AND m.del_flag = '0'
        <if test="keyword != null and keyword != ''">
          AND (m.subject_id ILIKE '%' || #{keyword} || '%' OR m.display_name ILIKE '%' || #{keyword} || '%')
        </if>
        ORDER BY m.display_name NULLS LAST, m.subject_id
        </script>
        """)
    com.baomidou.mybatisplus.core.metadata.IPage<ProfileSegmentMemberRow> selectMemberPage(
        com.baomidou.mybatisplus.core.metadata.IPage<ProfileSegmentMemberRow> page,
        @Param("tenantId") String tenantId,
        @Param("segmentId") Long segmentId,
        @Param("runId") Long runId,
        @Param("keyword") String keyword);
}
