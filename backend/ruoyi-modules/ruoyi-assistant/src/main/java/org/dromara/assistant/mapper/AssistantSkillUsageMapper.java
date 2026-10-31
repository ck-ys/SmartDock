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
     */
    @Insert("INSERT INTO dcs_assistant_skill_usage (id, tenant_id, skill_id, use_count, last_used_at, update_time)"
        + " VALUES (#{id}, #{tenantId}, #{skillId}, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (tenant_id, skill_id) DO UPDATE SET"
        + " use_count = dcs_assistant_skill_usage.use_count + 1,"
        + " last_used_at = CURRENT_TIMESTAMP, update_time = CURRENT_TIMESTAMP")
    int bump(@Param("id") Long id, @Param("tenantId") String tenantId, @Param("skillId") String skillId);

    /** 管理列表一次取全租户统计（技能数是个位数~几十，不做分页）。 */
    @Select("SELECT skill_id AS skillid, use_count AS usecount, last_used_at AS lastusedat"
        + " FROM dcs_assistant_skill_usage WHERE tenant_id = #{tenantId}")
    List<Map<String, Object>> selectByTenant(@Param("tenantId") String tenantId);
}
