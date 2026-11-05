package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.assistant.domain.DcsAssistantSkillUsage;

import java.util.List;
import java.util.Map;

/**
 * 技能使用统计 Mapper（题2 案3，2026-10-31）。
 *
 * <p>类级 {@code @InterceptorIgnore(tenantLine="true")}：写方是助手运行时线程（无 Sa-Token），
 * 租户一律显式传参、SQL 自带 tenant_id（与 AssistantUserQuotaMapper 同款口径）。</p>
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantSkillUsageMapper extends BaseMapper<DcsAssistantSkillUsage> {

    /**
     * 取用一次：首插 use_count=1，在位自增；last_used_at/update_time 一起刷新。
     * 并发同技能两次调用由唯一索引 + 原子自增消化，不需要先读后写。
     * 批3.5·题5：键加 user_id——「你最近用过」按人记账；历史单位级行（user_id=0）不再新增。
     */
    @Insert("INSERT INTO dcs_assistant_skill_usage (id, tenant_id, user_id, skill_id, use_count, last_used_at, update_time)"
        + " VALUES (#{id}, #{tenantId}, #{userId}, #{skillId}, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (tenant_id, user_id, skill_id) DO UPDATE SET"
        + " use_count = dcs_assistant_skill_usage.use_count + 1,"
        + " last_used_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP")
    int bump(@Param("id") Long id, @Param("tenantId") String tenantId, @Param("userId") Long userId,
             @Param("skillId") String skillId);

    /** 管理列表一次取全租户统计（技能数是个位数~几十，不做分页）。
     *  批3.5·题5：加了人维度后按技能聚合（SUM 次数 / MAX 最近），管理页口径不变=全单位合计。 */
    @Select("SELECT skill_id AS skillid, SUM(use_count) AS usecount, MAX(last_used_at) AS lastusedat"
        + " FROM dcs_assistant_skill_usage WHERE tenant_id = #{tenantId} GROUP BY skill_id")
    List<Map<String, Object>> selectByTenant(@Param("tenantId") String tenantId);

    /** 批3.5·题5：某人最近用过的技能（use_count>0，按最近一次倒序，LIMIT 由调用方定）。 */
    @Select("SELECT skill_id AS skillid, use_count AS usecount, last_used_at AS lastusedat"
        + " FROM dcs_assistant_skill_usage WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND use_count > 0"
        + " ORDER BY last_used_at DESC LIMIT #{limit}")
    List<Map<String, Object>> selectRecentByUser(@Param("tenantId") String tenantId,
                                                 @Param("userId") Long userId, @Param("limit") int limit);
}
