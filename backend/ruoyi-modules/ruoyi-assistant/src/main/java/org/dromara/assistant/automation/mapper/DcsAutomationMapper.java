package org.dromara.assistant.automation.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.assistant.automation.domain.DcsAutomation;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动化定义 Mapper（2026-11-05 自动化批）。
 *
 * <p>类级 {@code @InterceptorIgnore(tenantLine="true")}：扫描器在无登录上下文的线程里跨租户取
 * 到期行，SQL 内自带 tenant_id 条件（与 AssistantRunMapper 同纪律）。</p>
 */
@InterceptorIgnore(tenantLine = "true")
public interface DcsAutomationMapper extends BaseMapper<DcsAutomation> {

    /**
     * 本人可见列表 = 自己的自建项 + 本租户内置项（内置排最后）。
     *
     * <p>filter 三段只作用于**自建项**：内置项开关的真相在租户配置（{@code digest_enabled}），
     * 表里的 enabled 列只是占位，按列筛会筛错（席3 P2-5/P2-6）。列表页在本地按 VO 的
     * enabled（配置派生）筛选，本参数留给直接调接口的调用方。</p>
     */
    @Select("<script>"
        + "SELECT * FROM dcs_automation WHERE tenant_id=#{tenantId} AND del_flag='0'"
        + " AND (user_id=#{userId} OR builtin='1')"
        + "<if test=\"filter == 'ENABLED'\"> AND enabled='1' AND builtin='0'</if>"
        + "<if test=\"filter == 'PAUSED'\"> AND enabled='0' AND builtin='0'</if>"
        + "<if test=\"filter == 'FAILED'\"> AND last_status='FAILED' AND builtin='0'</if>"
        + " ORDER BY builtin ASC, create_time DESC"
        + "</script>")
    List<DcsAutomation> selectVisible(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                                      @Param("filter") String filter);

    @Select("SELECT * FROM dcs_automation WHERE tenant_id=#{tenantId} AND id=#{id} AND del_flag='0'")
    DcsAutomation selectOne(@Param("tenantId") String tenantId, @Param("id") Long id);

    @Select("SELECT * FROM dcs_automation WHERE tenant_id=#{tenantId} AND builtin='1' AND del_flag='0' LIMIT 1")
    DcsAutomation selectBuiltin(@Param("tenantId") String tenantId);

    @Select("SELECT count(1) FROM dcs_automation WHERE tenant_id=#{tenantId} AND user_id=#{userId}"
        + " AND builtin='0' AND del_flag='0'")
    int countOwn(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /** 同一用户并发新建串行化（5 条上限是 check-then-act，不加锁会超卖） */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{tenantId} || ':automation:' || #{userId}, 0))")
    String lockUserSlot(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    @Insert("INSERT INTO dcs_automation (id, tenant_id, user_id, title, prompt, schedule_type, schedule_time,"
        + " weekdays, time_zone, enabled, builtin, consecutive_failures, next_run_at, create_by, create_time, del_flag)"
        + " VALUES (#{a.id}, #{a.tenantId}, #{a.userId}, #{a.title}, #{a.prompt}, #{a.scheduleType},"
        + " #{a.scheduleTime}, #{a.weekdays}, #{a.timeZone}, #{a.enabled}, #{a.builtin}, 0,"
        + " #{a.nextRunAt}, #{a.createBy}, CURRENT_TIMESTAMP, '0')")
    int insertOne(@Param("a") DcsAutomation automation);

    /** 内置项懒建（每租户一条；唯一索引 uk_dcs_automation_builtin 吸收并发） */
    @Insert("INSERT INTO dcs_automation (id, tenant_id, user_id, title, prompt, schedule_type, time_zone,"
        + " enabled, builtin, consecutive_failures, create_by, create_time, del_flag)"
        + " VALUES (#{a.id}, #{a.tenantId}, #{a.userId}, #{a.title}, #{a.prompt}, 'BUILTIN',"
        + " #{a.timeZone}, #{a.enabled}, '1', 0, 0, CURRENT_TIMESTAMP, '0')"
        + " ON CONFLICT DO NOTHING")
    int insertBuiltinIgnore(@Param("a") DcsAutomation automation);

    /** 编辑（仅本人自建项；builtin 不可编辑） */
    @Update("UPDATE dcs_automation SET title=#{a.title}, prompt=#{a.prompt}, schedule_type=#{a.scheduleType},"
        + " schedule_time=#{a.scheduleTime}, weekdays=#{a.weekdays}, enabled=#{a.enabled},"
        + " next_run_at=#{a.nextRunAt}, consecutive_failures=0, update_by=#{a.updateBy}, update_time=CURRENT_TIMESTAMP"
        + " WHERE id=#{a.id} AND tenant_id=#{a.tenantId} AND user_id=#{a.userId} AND builtin='0' AND del_flag='0'")
    int updateOwn(@Param("a") DcsAutomation automation);

    /** 开关（自建项；开=重算下次，关=清空下次） */
    @Update("UPDATE dcs_automation SET enabled=#{enabled}, next_run_at=#{nextRunAt},"
        + " consecutive_failures=CASE WHEN #{enabled}='1' THEN 0 ELSE consecutive_failures END,"
        + " update_by=#{userId}, update_time=CURRENT_TIMESTAMP"
        + " WHERE id=#{id} AND tenant_id=#{tenantId} AND user_id=#{userId} AND builtin='0' AND del_flag='0'")
    int toggleOwn(@Param("tenantId") String tenantId, @Param("userId") Long userId, @Param("id") Long id,
                  @Param("enabled") String enabled, @Param("nextRunAt") LocalDateTime nextRunAt);

    /** 内置项开关（租户级，调用方已校验配置权限） */
    @Update("UPDATE dcs_automation SET enabled=#{enabled}, update_by=#{userId}, update_time=CURRENT_TIMESTAMP"
        + " WHERE id=#{id} AND tenant_id=#{tenantId} AND builtin='1' AND del_flag='0'")
    int toggleBuiltin(@Param("tenantId") String tenantId, @Param("userId") Long userId,
                      @Param("id") Long id, @Param("enabled") String enabled);

    @Update("UPDATE dcs_automation SET del_flag='1', next_run_at=NULL, update_time=CURRENT_TIMESTAMP"
        + " WHERE id=#{id} AND tenant_id=#{tenantId} AND user_id=#{userId} AND builtin='0' AND del_flag='0'")
    int softDeleteOwn(@Param("tenantId") String tenantId, @Param("userId") Long userId, @Param("id") Long id);

    /**
     * 扫描器认领：到期且开启的行，行级锁 + SKIP LOCKED（多实例各领各的）。
     * 必须在事务内调用；拿到行后先推进 next_run_at 再执行（open-webui 同款）。
     */
    @Select("SELECT * FROM dcs_automation WHERE del_flag='0' AND enabled='1' AND builtin='0'"
        + " AND next_run_at IS NOT NULL AND next_run_at <= #{now}"
        + " ORDER BY next_run_at ASC LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<DcsAutomation> claimDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE dcs_automation SET next_run_at=#{nextRunAt} WHERE id=#{id} AND tenant_id=#{tenantId}")
    int advanceNextRun(@Param("tenantId") String tenantId, @Param("id") Long id,
                       @Param("nextRunAt") LocalDateTime nextRunAt);

    /** 认领即写「最近一次」状态（RUNNING），卡片/列表刷新能看到在跑 */
    @Update("UPDATE dcs_automation SET last_run_at=#{at}, last_status=#{status}, last_run_id=#{runId},"
        + " update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND tenant_id=#{tenantId}")
    int markRun(@Param("tenantId") String tenantId, @Param("id") Long id,
                @Param("status") String status, @Param("runId") Long runId, @Param("at") LocalDateTime at);

    @Update("UPDATE dcs_automation SET consecutive_failures=0 WHERE id=#{id} AND tenant_id=#{tenantId}")
    int resetFailures(@Param("tenantId") String tenantId, @Param("id") Long id);

    /** 失败计数 +1 并返回新值（达到 3 由 service 停用） */
    @Select("UPDATE dcs_automation SET consecutive_failures=consecutive_failures+1,"
        + " update_time=CURRENT_TIMESTAMP WHERE id=#{id} AND tenant_id=#{tenantId}"
        + " RETURNING consecutive_failures")
    Integer incrementFailures(@Param("tenantId") String tenantId, @Param("id") Long id);

    /** 连续失败 3 次自动停用（题6 甲） */
    @Update("UPDATE dcs_automation SET enabled='0', next_run_at=NULL, update_time=CURRENT_TIMESTAMP"
        + " WHERE id=#{id} AND tenant_id=#{tenantId} AND builtin='0'")
    int disable(@Param("tenantId") String tenantId, @Param("id") Long id);
}
