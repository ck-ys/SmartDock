package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.assistant.domain.DcsAssistantSkill;

import java.util.List;

/**
 * 技能在线层 Mapper（5b 批）。
 *
 * <p>读取要「全局基线（000000）＋本租户」两层一起拿（租户行覆盖同名全局行），跨租户条件
 * 必须手写——禁租户插件注入，SQL 自带租户条件（仓根 AGENTS 多租户铁律）。</p>
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantSkillMapper extends BaseMapper<DcsAssistantSkill> {

    /**
     * 生效行（disabled=0）：全局基线＋本租户两层；同 skill_id 租户行胜由调用方归并。
     * 管理列表用 {@link #selectAllForTenant}（含停用行）。
     */
    @Select("SELECT * FROM dcs_assistant_skill WHERE disabled = 0"
        + " AND tenant_id IN ('000000', #{tenantId}) ORDER BY skill_id, tenant_id")
    List<DcsAssistantSkill> selectEffective(@Param("tenantId") String tenantId);

    /** 管理列表：全局基线＋本租户全部行（含停用，管理台要看全量） */
    @Select("SELECT * FROM dcs_assistant_skill WHERE tenant_id IN ('000000', #{tenantId})"
        + " ORDER BY skill_id, tenant_id")
    List<DcsAssistantSkill> selectAllForTenant(@Param("tenantId") String tenantId);
}
