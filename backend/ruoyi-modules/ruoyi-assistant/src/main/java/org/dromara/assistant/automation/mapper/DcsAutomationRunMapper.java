package org.dromara.assistant.automation.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.assistant.automation.domain.DcsAutomationRun;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动化运行记录 Mapper（2026-11-05 自动化批）。
 *
 * <p>两个部分唯一索引兜底：同一自动化同一计划时刻只一行（SCHEDULE）、同一自动化同时只一个
 * RUNNING。插入统一 {@code ON CONFLICT DO NOTHING}，返回 0 = 被去重挡下。</p>
 */
@InterceptorIgnore(tenantLine = "true")
public interface DcsAutomationRunMapper extends BaseMapper<DcsAutomationRun> {

    @Insert("INSERT INTO dcs_automation_run (id, tenant_id, automation_id, user_id, trigger_type,"
        + " scheduled_for, status, thread_id, started_at, create_time)"
        + " VALUES (#{r.id}, #{r.tenantId}, #{r.automationId}, #{r.userId}, #{r.triggerType},"
        + " #{r.scheduledFor}, #{r.status}, #{r.threadId}, #{r.startedAt}, CURRENT_TIMESTAMP)"
        + " ON CONFLICT DO NOTHING")
    int insertIgnore(@Param("r") DcsAutomationRun run);

    @Select("SELECT * FROM dcs_automation_run WHERE tenant_id=#{tenantId} AND id=#{id}")
    DcsAutomationRun selectOne(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Select("SELECT * FROM dcs_automation_run WHERE tenant_id=#{tenantId} AND automation_id=#{automationId}"
        + " ORDER BY scheduled_for DESC, id DESC LIMIT #{limit}")
    List<DcsAutomationRun> selectByAutomation(@Param("tenantId") String tenantId,
                                              @Param("automationId") Long automationId,
                                              @Param("limit") int limit);

    /** 「最近运行」横跨本人自建项 + 本租户内置项（内置运行 user_id=0）；已删自动化不再出现 */
    @Select("SELECT r.*, a.title AS automation_title FROM dcs_automation_run r"
        + " JOIN dcs_automation a ON a.id = r.automation_id AND a.tenant_id = r.tenant_id"
        + " WHERE r.tenant_id=#{tenantId} AND a.del_flag='0' AND (r.user_id=#{userId} OR a.builtin='1')"
        + " ORDER BY r.scheduled_for DESC, r.id DESC LIMIT #{limit}")
    List<DcsAutomationRun> selectRecent(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                                        @Param("limit") int limit);

    @Select("SELECT count(1) FROM dcs_automation_run WHERE tenant_id=#{tenantId} AND automation_id=#{automationId}")
    int countByAutomation(@Param("tenantId") String tenantId, @Param("automationId") Long automationId);

    @Select("SELECT count(1) FROM dcs_automation_run WHERE tenant_id=#{tenantId} AND automation_id=#{automationId}"
        + " AND status='SUCCESS'")
    int countSuccess(@Param("tenantId") String tenantId, @Param("automationId") Long automationId);

    /** 收口：只从 RUNNING 迁出（迟到/重复回调不覆盖已终态） */
    @Update("UPDATE dcs_automation_run SET status=#{status}, result_summary=#{summary},"
        + " error_message=#{error}, duration_ms=#{durationMs}, finished_at=CURRENT_TIMESTAMP,"
        + " conversation_id=COALESCE(#{conversationId}, conversation_id),"
        + " thread_id=COALESCE(#{threadId}, thread_id),"
        + " started_at=COALESCE(started_at, CURRENT_TIMESTAMP)"
        + " WHERE id=#{id} AND tenant_id=#{tenantId} AND status='RUNNING'")
    int finish(@Param("tenantId") String tenantId, @Param("id") Long id, @Param("status") String status,
               @Param("summary") String summary, @Param("error") String error,
               @Param("durationMs") Integer durationMs,
               @Param("conversationId") Long conversationId, @Param("threadId") String threadId);

    /** 真正开跑时回写开始时间：认领后可能排队很久，超时判据必须按「实际执行」算，不按「被认领」算 */
    @Update("UPDATE dcs_automation_run SET started_at=#{now} WHERE id=#{id} AND status='RUNNING'")
    int touchStarted(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 悬挂 RUNNING 收口（进程崩溃/超时）；返回受影响行数 */
    @Select("SELECT * FROM dcs_automation_run WHERE status='RUNNING' AND started_at < #{before}")
    List<DcsAutomationRun> selectStale(@Param("before") LocalDateTime before);

    /** MISSED 行补结束时间（错过补跑窗口，没有执行过程） */
    @Update("UPDATE dcs_automation_run SET finished_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='MISSED'")
    int finishMissed(@Param("id") Long id);

    /** 超时收口：时长用传入的 Java 时刻算（与 started_at 同一时钟，不受库会话时区影响） */
    @Update("UPDATE dcs_automation_run SET status='FAILED', error_message=#{error},"
        + " finished_at=#{now},"
        + " duration_ms=COALESCE((EXTRACT(EPOCH FROM (#{now} - started_at)) * 1000)::int, duration_ms)"
        + " WHERE id=#{id} AND status='RUNNING'")
    int failStale(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("error") String error);

    /** 同一自动化是否还有在跑（手动「立即运行」去重提示用） */
    @Select("SELECT count(1) FROM dcs_automation_run WHERE tenant_id=#{tenantId}"
        + " AND automation_id=#{automationId} AND status='RUNNING'")
    int countRunning(@Param("tenantId") String tenantId, @Param("automationId") Long automationId);
}
