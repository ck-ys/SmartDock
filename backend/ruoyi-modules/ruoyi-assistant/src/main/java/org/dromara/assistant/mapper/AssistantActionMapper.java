package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.assistant.domain.DcsAssistantAction;

/**
 * 助手动作账本 Mapper（批G G3）。幂等键唯一索引=双发防线（@RepeatSubmit 替代件）。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantActionMapper extends BaseMapper<DcsAssistantAction> {

    /** 账本落行（幂等键冲突=同键重试，DO NOTHING 后由 service 读回原行判定） */
    @Insert("INSERT INTO dcs_assistant_action (id, tenant_id, user_id, run_id, thread_id, tool_name, args_json,"
        + " status, idempotency_key, create_time) VALUES (#{a.id}, #{a.tenantId}, #{a.userId}, #{a.runId},"
        + " #{a.threadId}, #{a.toolName}, #{a.argsJson}, #{a.status}, #{a.idempotencyKey}, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (idempotency_key) DO NOTHING")
    int insertIgnoreKey(@Param("a") DcsAssistantAction action);

    /** 幂等键读回（重试判定：DONE=回执复述；EXECUTING=拒绝重复触发） */
    @Select("SELECT * FROM dcs_assistant_action WHERE idempotency_key=#{key}")
    DcsAssistantAction selectByKey(@Param("key") String key);

    /** 状态流转（EXECUTING→DONE/FAILED；PENDING→REJECTED） */
    @Update("UPDATE dcs_assistant_action SET status=#{status}, result_summary=#{summary}, update_time=CURRENT_TIMESTAMP"
        + " WHERE id=#{id}")
    int mark(@Param("id") Long id, @Param("status") String status, @Param("summary") String summary);

    /** 按 run 查账本（断线重连恢复数据源，spec §18 action/list） */
    @Select("SELECT * FROM dcs_assistant_action WHERE tenant_id=#{tenantId} AND user_id=#{userId}"
        + " AND (#{runId}::varchar IS NULL OR run_id=#{runId}) AND (#{threadId}::varchar IS NULL OR thread_id=#{threadId})"
        + " ORDER BY id DESC LIMIT 100")
    java.util.List<DcsAssistantAction> selectForUser(@Param("tenantId") String tenantId,
        @Param("userId") Long userId, @Param("runId") String runId, @Param("threadId") String threadId);

    /** 保留期清理：action>180 天分批物理删（归档=删除前计数进 job 日志，P2 冷备） */
    @Delete("DELETE FROM dcs_assistant_action WHERE id IN (SELECT id FROM dcs_assistant_action"
        + " WHERE create_time < (CURRENT_TIMESTAMP - INTERVAL '180 days') LIMIT #{batch})")
    int deleteExpired(@Param("batch") int batch);

    /** 二站① AU-52：管理端运行记录——按租户倒序分页（操作人昵称左连；雪花 user_id 全局唯一）
     * 2026-10-11 日常查询：keyword 模糊命中 动作/结果摘要/操作人昵称 三列（ILIKE 免大小写）+ status 精确 + create_time 起闭区间 */
    @Select("<script>SELECT a.id, a.tool_name, a.status, a.result_summary, a.thread_id, a.create_time,"
        + " COALESCE(u.nick_name, '') AS user_name FROM dcs_assistant_action a"
        + " LEFT JOIN sys_user u ON u.user_id = a.user_id"
        + " WHERE a.tenant_id = #{tenantId}"
        + " <if test='keyword != null and keyword != \"\"'>AND (a.tool_name ILIKE '%' || #{keyword} || '%'"
        + " OR a.result_summary ILIKE '%' || #{keyword} || '%'"
        + " OR u.nick_name ILIKE '%' || #{keyword} || '%')</if>"
        + " <if test='status != null and status != \"\"'>AND a.status = #{status}</if>"
        + " <if test='beginTime != null and beginTime != \"\"'>AND a.create_time &gt;= CAST(#{beginTime} AS timestamp)</if>"
        + " <if test='endTime != null and endTime != \"\"'>AND a.create_time &lt;= CAST(#{endTime} AS timestamp)</if>"
        + " ORDER BY a.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    java.util.List<org.dromara.assistant.domain.AssistantActionLogVo> selectLogPage(
        @Param("tenantId") String tenantId, @Param("limit") int limit, @Param("offset") int offset,
        @Param("keyword") String keyword, @Param("status") String status,
        @Param("beginTime") String beginTime, @Param("endTime") String endTime);

    /** 二站① AU-52：运行记录总数（分页 total）；2026-10-11 与 selectLogPage 同条件口径，防止筛选后 total 与行数对不上 */
    @Select("<script>SELECT COUNT(*) FROM dcs_assistant_action a"
        + " LEFT JOIN sys_user u ON u.user_id = a.user_id"
        + " WHERE a.tenant_id = #{tenantId}"
        + " <if test='keyword != null and keyword != \"\"'>AND (a.tool_name ILIKE '%' || #{keyword} || '%'"
        + " OR a.result_summary ILIKE '%' || #{keyword} || '%'"
        + " OR u.nick_name ILIKE '%' || #{keyword} || '%')</if>"
        + " <if test='status != null and status != \"\"'>AND a.status = #{status}</if>"
        + " <if test='beginTime != null and beginTime != \"\"'>AND a.create_time &gt;= CAST(#{beginTime} AS timestamp)</if>"
        + " <if test='endTime != null and endTime != \"\"'>AND a.create_time &lt;= CAST(#{endTime} AS timestamp)</if>"
        + " </script>")
    long countForTenant(@Param("tenantId") String tenantId,
        @Param("keyword") String keyword, @Param("status") String status,
        @Param("beginTime") String beginTime, @Param("endTime") String endTime);

    /** 租户清除通道成员 */
    @Delete("DELETE FROM dcs_assistant_action WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    int purgeUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);
}
