package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileSegment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 标签分群 Mapper。rule_config 是 jsonb。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileSegmentMapper extends BaseMapperPlus<ProfileSegment, ProfileSegment> {

    @Insert("""
        INSERT INTO profile_segment
            (segment_id, tenant_id, source, segment_code, segment_name, segment_type, status,
             rule_config, description, member_count, last_run_id, last_eval_time, builtin,
             revision, create_by, create_time)
        VALUES
            (#{s.segmentId}, #{s.tenantId}, #{s.source}, #{s.segmentCode}, #{s.segmentName},
             #{s.segmentType}, #{s.status}, #{s.ruleConfig}::jsonb, #{s.description},
             #{s.memberCount}, #{s.lastRunId}, #{s.lastEvalTime}, #{s.builtin},
             #{s.revision}, #{s.createBy}, CURRENT_TIMESTAMP)
        """)
    int insertJsonb(@Param("s") ProfileSegment s);

    @Update("""
        UPDATE profile_segment
        SET segment_name = #{s.segmentName}, segment_type = #{s.segmentType}, status = #{s.status},
            rule_config = #{s.ruleConfig}::jsonb, description = #{s.description},
            member_count = #{s.memberCount}, last_run_id = #{s.lastRunId},
            last_eval_time = #{s.lastEvalTime}, revision = #{s.revision},
            update_by = #{s.updateBy}, update_time = CURRENT_TIMESTAMP
        WHERE segment_id = #{s.segmentId} AND tenant_id = #{s.tenantId} AND del_flag = '0'
        """)
    int updateJsonb(@Param("s") ProfileSegment s);

    @Update("""
        UPDATE profile_segment
        SET member_count = #{memberCount}, last_run_id = #{lastRunId}, last_eval_time = #{lastEvalTime},
            update_by = #{updateBy}, update_time = CURRENT_TIMESTAMP
        WHERE segment_id = #{segmentId} AND tenant_id = #{tenantId} AND del_flag = '0'
        """)
    int updateRunStats(@Param("segmentId") Long segmentId,
                       @Param("tenantId") String tenantId,
                       @Param("memberCount") int memberCount,
                       @Param("lastRunId") Long lastRunId,
                       @Param("lastEvalTime") java.util.Date lastEvalTime,
                       @Param("updateBy") Long updateBy);

    @Update("""
        UPDATE profile_segment
        SET status = #{status}, update_by = #{updateBy}, update_time = CURRENT_TIMESTAMP
        WHERE segment_id = #{segmentId} AND tenant_id = #{tenantId} AND del_flag = '0'
        """)
    int updateStatus(@Param("segmentId") Long segmentId,
                     @Param("tenantId") String tenantId,
                     @Param("status") String status,
                     @Param("updateBy") Long updateBy);
}
