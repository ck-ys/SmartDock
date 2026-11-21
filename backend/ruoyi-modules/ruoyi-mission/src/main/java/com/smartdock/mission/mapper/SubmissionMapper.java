package com.smartdock.mission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdock.mission.domain.Submission;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 微核心: 提交 Mapper。
 *
 * <p>提交不可修改(重提=新版本行), data 只在 insert 出现;
 * 状态迁移 CAS 防并发双审。</p>
 *
 * @author smartdock
 */
@Mapper
public interface SubmissionMapper extends BaseMapper<Submission> {

    @Insert("""
        INSERT INTO submission
            (id, tenant_id, task_id, submitter_user_id, version, status, data, submitted_at, create_time)
        VALUES
            (#{s.id}, #{s.tenantId}, #{s.taskId}, #{s.submitterUserId}, #{s.version}, #{s.status},
             #{s.data}::jsonb, #{s.submittedAt}, CURRENT_TIMESTAMP)
        """)
    int insertJsonb(@Param("s") Submission s);

    /** 审核落状态 CAS: 仅当仍 PENDING_AUDIT 才生效; 0 行=已被并发审核 */
    @Update("UPDATE submission SET status = #{to}, audited_at = CURRENT_TIMESTAMP "
        + "WHERE id = #{id} AND status = 'PENDING_AUDIT'")
    int auditStatus(@Param("id") Long id, @Param("to") String to);
}
