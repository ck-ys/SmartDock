package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.assistant.domain.DcsAssistantRun;

/**
 * 助手 run 登记 Mapper（批G G3）。runId 首绑归属（S-04①）+ 终态回写。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantRunMapper extends BaseMapper<DcsAssistantRun> {

    /** runId 登记制首绑（唯一索引吸收并发；重放同 runId 换人=service 校验 403） */
    @Insert("INSERT INTO dcs_assistant_run (id, tenant_id, user_id, thread_id, run_id, status, create_time)"
        + " VALUES (#{r.id}, #{r.tenantId}, #{r.userId}, #{r.threadId}, #{r.runId}, 'RUNNING', CURRENT_TIMESTAMP)"
        + " ON CONFLICT (run_id) DO NOTHING")
    int insertIgnoreRun(@Param("r") DcsAssistantRun run);

    /** 按 runId 取登记行（enricher 侧归属回查锚点） */
    @Select("SELECT * FROM dcs_assistant_run WHERE run_id=#{runId}")
    DcsAssistantRun selectByRunId(@Param("runId") String runId);

    /** 同 (租户,用户,thread) 发送串行锁（题5 三审 dsflash-P1/glm53-P2：双 RUNNING 门是
     *  check-then-act，READ COMMITTED 下两个并发事务互看不见对方未提交行可双双过门=双流
     *  并发写同一 AgentSession/双扣费）。事务级咨询锁：并发第二发阻塞到第一发提交后必然
     *  看到 RUNNING 行走 409；锁随事务提交/回滚自动释放，不同 thread 互不阻塞，跨实例
     *  （多实例批后）同库同样生效。注意返回类型必须 String——PG 咨询锁函数返回 void 列、
     *  JDBC 以空串送达：void 声明走结果构造炸 No constructor found、Long 声明 getLong
     *  解析空串炸不良类型值（两轮实测，均被 resolver 当 PG 闪断降级=user 消息/run 行全丢）；
     *  String 映射空串安全，返回值无意义 */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{tenantId} || ':' || #{userId} || ':' || #{threadId}, 0))")
    String acquireThreadSendLock(@Param("tenantId") String tenantId, @Param("userId") Long userId,
        @Param("threadId") String threadId);

    /** 终态回写（COMPLETED/FAILED/INTERRUPTED）。批6-#2 加 status='RUNNING' 条件：
     *  失联收割器已把停滞 run 迁 INTERRUPTED 后，迟到的事件流终态不得覆写回去
     *  （历史语义不变——所有真实终态迁移本就是 RUNNING→X） */
    @Update("UPDATE dcs_assistant_run SET status=#{status}, end_reason=#{endReason}, update_time=CURRENT_TIMESTAMP"
        + " WHERE run_id=#{runId} AND status='RUNNING'")
    int finish(@Param("runId") String runId, @Param("status") String status, @Param("endReason") String endReason);

    /** 按 thread 查 run（断线恢复列表，最新在前） */
    @Select("SELECT * FROM dcs_assistant_run WHERE tenant_id=#{tenantId} AND thread_id=#{threadId} AND user_id=#{userId}"
        + " ORDER BY id DESC LIMIT #{limit}")
    java.util.List<DcsAssistantRun> selectByThread(@Param("tenantId") String tenantId,
        @Param("threadId") String threadId, @Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 用户当前全部 RUNNING 的 thread 集合（业务批4 Q4）：会话列表回填 running 标志用。
     * distinct——同 thread 理论至多一个在途轮（后端串行），并发异常时也只取集合语义。
     */
    @Select("SELECT DISTINCT thread_id FROM dcs_assistant_run"
        + " WHERE tenant_id=#{tenantId} AND user_id=#{userId} AND status='RUNNING'")
    java.util.List<String> selectRunningThreadIds(@Param("tenantId") String tenantId,
        @Param("userId") Long userId);

    /** 保留期清理：run>180 天分批物理删（与 conversation 同期，登记项） */
    @Delete("DELETE FROM dcs_assistant_run WHERE id IN (SELECT id FROM dcs_assistant_run"
        + " WHERE create_time < (CURRENT_TIMESTAMP - INTERVAL '180 days') LIMIT #{batch})")
    int deleteExpired(@Param("batch") int batch);

    /** 租户清除通道成员 */
    @Delete("DELETE FROM dcs_assistant_run WHERE tenant_id=#{tenantId} AND user_id=#{userId}")
    int purgeUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /**
     * 悬挂 run 兜底（dspro P1-1）：resolve 已落库但事件流未产出任何终态（打错
     * agentId/框架侧同步异常走 SSE 错误帧路径）→ RUNNING 永挂。超时行迁移
     * INTERRUPTED 并 RETURNING 归属供 job 退回合——UPDATE...RETURNING 走 @Select
     * 执行器（PG 允许 executeQuery 携带 RETURNING），迁移成功的行才退，天然防双退。
     */
    @Select("UPDATE dcs_assistant_run SET status='INTERRUPTED', end_reason='no-terminal-event',"
        + " update_time=CURRENT_TIMESTAMP WHERE status='RUNNING'"
        + " AND create_time < (CURRENT_TIMESTAMP - make_interval(mins => #{minutes}))"
        + " RETURNING tenant_id, user_id, run_id")
    java.util.List<DcsAssistantRun> closeStuck(@Param("minutes") int minutes);

    /**
     * 批6-#2 失联收割（刷新/断网拆链的 run）：RunRecorder 实测「事件停滞超阈值」后
     * 按 runId 条件迁移 INTERRUPTED/'client-disconnect'——条件 RUNNING 保证与真实
     * 终态竞态时只有一方赢（同 closeStuck 的 RETURNING 防双退语义）。
     * m3 P1-1 回修补租户谓词：纵深防御（雪花全局唯一本无越权面，风格对齐
     * updateFollowupSuggestions）；tenantId 传 null 时恒不命中，与 run 行不存在同结果无回归。
     */
    @Select("UPDATE dcs_assistant_run SET status='INTERRUPTED', end_reason='client-disconnect',"
        + " update_time=CURRENT_TIMESTAMP WHERE run_id=#{runId} AND tenant_id=#{tenantId} AND status='RUNNING'"
        + " RETURNING tenant_id, user_id, run_id")
    java.util.List<DcsAssistantRun> finalizeStalled(@Param("tenantId") String tenantId, @Param("runId") String runId);

    /**
     * 业务批10 #2：RUNNING 超时行扫描（收割器零事件二扫用）。建行后从未有 enricher
     * 事件的 run 不在记录器 lastEventAt 表里，停滞收割失明（15min closeStuck 才兜底）；
     * 按 create_time 捞出候选，收割器差集过滤后条件迁移。
     */
    @Select("SELECT * FROM dcs_assistant_run WHERE status='RUNNING'"
        + " AND create_time < (CURRENT_TIMESTAMP - make_interval(secs => #{seconds}))")
    java.util.List<DcsAssistantRun> selectRunningOlderThan(@Param("seconds") int seconds);
}
