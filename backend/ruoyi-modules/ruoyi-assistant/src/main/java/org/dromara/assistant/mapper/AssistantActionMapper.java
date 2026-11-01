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

    /** 账本落行（幂等键冲突=同键重试，DO NOTHING 后由 service 读回原行判定）；
     *  三批③：多落 release_mode（confirm/once/auto/allow；历史/拒绝行为空） */
    @Insert("INSERT INTO dcs_assistant_action (id, tenant_id, user_id, run_id, thread_id, tool_name, args_json,"
        + " status, idempotency_key, release_mode, create_time) VALUES (#{a.id}, #{a.tenantId}, #{a.userId}, #{a.runId},"
        + " #{a.threadId}, #{a.toolName}, #{a.argsJson}, #{a.status}, #{a.idempotencyKey}, #{a.releaseMode}, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (idempotency_key) DO NOTHING")
    int insertIgnoreKey(@Param("a") DcsAssistantAction action);

    /** 拒绝留痕行（G1 乙类#2）：多带 result_summary（拒绝原因）；幂等键=双发防线 */
    @Insert("INSERT INTO dcs_assistant_action (id, tenant_id, user_id, run_id, thread_id, tool_name,"
        + " status, result_summary, idempotency_key, create_time) VALUES (#{a.id}, #{a.tenantId}, #{a.userId},"
        + " #{a.runId}, #{a.threadId}, #{a.toolName}, #{a.status}, #{a.resultSummary}, #{a.idempotencyKey}, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (idempotency_key) DO NOTHING")
    int insertRejected(@Param("a") DcsAssistantAction action);

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

    /** 三批③（2026-11-01）：写操作审计分页——四维筛选（keyword=成员昵称 ILIKE / toolName
     * 精确 / releaseMode 精确 / create_time 闭区间），ORDER BY id DESC；不选 args_json
     * （管理面只看动作与结果）。不复用 selectLogPage：维度不同（该查昵称/工具/放行方式），
     * 且本组查询要 release_mode/user_id 出参。 */
    @Select("<script>SELECT a.id, a.tool_name, a.status, a.result_summary, a.thread_id, a.create_time,"
        + " a.release_mode, a.user_id, COALESCE(u.nick_name, '') AS user_name"
        + " FROM dcs_assistant_action a"
        + " LEFT JOIN sys_user u ON u.user_id = a.user_id"
        + " WHERE a.tenant_id = #{tenantId}"
        + " <if test='keyword != null and keyword != \"\"'>AND u.nick_name ILIKE '%' || #{keyword} || '%'</if>"
        + " <if test='toolName != null and toolName != \"\"'>AND a.tool_name = #{toolName}</if>"
        + " <if test='releaseMode != null and releaseMode != \"\"'>AND a.release_mode = #{releaseMode}</if>"
        + " <if test='beginTime != null and beginTime != \"\"'>AND a.create_time &gt;= CAST(#{beginTime} AS timestamp)</if>"
        + " <if test='endTime != null and endTime != \"\"'>AND a.create_time &lt;= CAST(#{endTime} AS timestamp)</if>"
        + " ORDER BY a.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    java.util.List<org.dromara.assistant.domain.AssistantActionLogVo> selectAuditPage(
        @Param("tenantId") String tenantId, @Param("limit") int limit, @Param("offset") int offset,
        @Param("keyword") String keyword, @Param("toolName") String toolName,
        @Param("releaseMode") String releaseMode,
        @Param("beginTime") String beginTime, @Param("endTime") String endTime);

    /** 三批③：审计总数（与 selectAuditPage 同条件口径，防止筛选后 total 与行数对不上） */
    @Select("<script>SELECT COUNT(*) FROM dcs_assistant_action a"
        + " LEFT JOIN sys_user u ON u.user_id = a.user_id"
        + " WHERE a.tenant_id = #{tenantId}"
        + " <if test='keyword != null and keyword != \"\"'>AND u.nick_name ILIKE '%' || #{keyword} || '%'</if>"
        + " <if test='toolName != null and toolName != \"\"'>AND a.tool_name = #{toolName}</if>"
        + " <if test='releaseMode != null and releaseMode != \"\"'>AND a.release_mode = #{releaseMode}</if>"
        + " <if test='beginTime != null and beginTime != \"\"'>AND a.create_time &gt;= CAST(#{beginTime} AS timestamp)</if>"
        + " <if test='endTime != null and endTime != \"\"'>AND a.create_time &lt;= CAST(#{endTime} AS timestamp)</if>"
        + " </script>")
    long countAuditForTenant(@Param("tenantId") String tenantId,
        @Param("keyword") String keyword, @Param("toolName") String toolName,
        @Param("releaseMode") String releaseMode,
        @Param("beginTime") String beginTime, @Param("endTime") String endTime);

    /** 租户清除通道成员 */
    @Delete("DELETE FROM dcs_assistant_action WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    int purgeUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /** 三批④（2026-11-01）：撤回留痕——status=REVOKED + 撤回人/时刻（业务动作成功后
     *  由 service 同事务落，业务失败抛出回滚则不落 REVOKED） */
    @Update("UPDATE dcs_assistant_action SET status='REVOKED', revoked_by=#{revokedBy},"
        + " revoked_at=CURRENT_TIMESTAMP, update_time=CURRENT_TIMESTAMP WHERE id=#{id}")
    int markRevoked(@Param("id") Long id, @Param("revokedBy") Long revokedBy);

    /**
     * 三批④：完成帧按 run 取可撤行（DONE + 可撤工具 + 有「（记录 #id）」尾段）。
     *
     * <p><b>租户锚：按 run_id 全局直查、不带租户条件</b>——同一 run 的账本行租户来源有三：
     * 执行行=登录快照（AgentFactory→hook→ToolIdentity.from 即建行时快照）、REJECTED 行=
     * run 登记行租户（enricher 用 run.getTenantId()）、run 登记行本身=动态优先
     * （resolver resolveTenantId）——超管切租户会话里三类行可分落两租户，带租户条件查
     * 完成帧会查空→通知卡静默不发（fail-soft 但功能性缺陷）。run_id 全局唯一（run 登记
     * 行首绑校验过），单 run 行数个位数，弃用 idx_dcs_asst_act_run 的租户前缀可接受。
     * 工具白名单由 service 传参（REVOCABLE_TOOLS 单一来源，不在此处硬编码防清单漂移）。</p>
     */
    @Select("<script>SELECT id, tool_name, result_summary FROM dcs_assistant_action"
        + " WHERE run_id=#{runId} AND status='DONE' AND result_summary LIKE '%（记录 #%）'"
        + " AND tool_name IN <foreach collection='tools' item='t' open='(' separator=',' close=')'>#{t}</foreach>"
        + " ORDER BY id</script>")
    java.util.List<DcsAssistantAction> selectRevocableByRun(@Param("runId") String runId,
                                                            @Param("tools") java.util.Collection<String> tools);
}
