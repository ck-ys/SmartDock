package com.smartdock.dcs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdock.dcs.domain.DcsTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 微核心: 任务 Mapper。
 *
 * <p>jsonb 列(assignee/auditor)写入走定制 SQL 强转; 状态迁移走 CAS 式
 * {@link #transitionStatus}(WHERE status=from, 返回 0=并发/非法竞态)。</p>
 *
 * @author smartdock
 */
@Mapper
public interface DcsTaskMapper extends BaseMapper<DcsTask> {

    @Insert("""
        INSERT INTO dcs_task
            (id, tenant_id, title, description, publisher_user_id, status, deadline,
             assignee_user_ids, audit_mode, auditor_user_ids, create_dept, create_by, create_time)
        VALUES
            (#{t.id}, #{t.tenantId}, #{t.title}, #{t.description}, #{t.publisherUserId}, #{t.status},
             #{t.deadline}, #{t.assigneeUserIds}::jsonb, #{t.auditMode}, #{t.auditorUserIds}::jsonb,
             #{t.createDept}, #{t.createBy}, CURRENT_TIMESTAMP)
        """)
    int insertJsonb(@Param("t") DcsTask t);

    /** DRAFT 期编辑(标题/描述/截止/成员/审核模式); 状态列不动 */
    @Update("""
        UPDATE dcs_task SET
            title = #{t.title},
            description = #{t.description},
            deadline = #{t.deadline},
            assignee_user_ids = #{t.assigneeUserIds}::jsonb,
            audit_mode = #{t.auditMode},
            auditor_user_ids = #{t.auditorUserIds}::jsonb,
            update_by = #{t.updateBy},
            update_time = CURRENT_TIMESTAMP
        WHERE id = #{t.id} AND status = 'DRAFT' -- 三审 dspro P1-2: 无状态谓词可并发改已发布任务
        """)
    int updateDraftJsonb(@Param("t") DcsTask t);

    /** 状态迁移 CAS: 仅当当前状态=from 才迁到 to; 0 行=状态已被并发改变 */
    @Update("UPDATE dcs_task SET status = #{to}, "
        + "publish_time = CASE WHEN #{to} = 'COLLECTING' THEN CURRENT_TIMESTAMP ELSE publish_time END, "
        + "update_time = CURRENT_TIMESTAMP "
        + "WHERE id = #{id} AND status = #{from}")
    int transitionStatus(@Param("id") Long id, @Param("from") String from, @Param("to") String to);
}
