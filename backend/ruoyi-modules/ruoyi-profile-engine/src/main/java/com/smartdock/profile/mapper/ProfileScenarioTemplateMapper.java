package com.smartdock.profile.mapper;

import com.smartdock.profile.domain.ProfileScenarioTemplate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 场景模板 Mapper。definition 是 jsonb。
 *
 * @author smartdock
 */
@Mapper
public interface ProfileScenarioTemplateMapper extends BaseMapperPlus<ProfileScenarioTemplate, ProfileScenarioTemplate> {

    @Insert("""
        INSERT INTO profile_scenario_template
            (template_id, tenant_id, template_code, template_name, scenario, description,
             definition, builtin, create_by, create_time)
        VALUES
            (#{t.templateId}, #{t.tenantId}, #{t.templateCode}, #{t.templateName}, #{t.scenario},
             #{t.description}, #{t.definition}::jsonb, #{t.builtin}, #{t.createBy}, CURRENT_TIMESTAMP)
        """)
    int insertJsonb(@Param("t") ProfileScenarioTemplate t);

    @Update("""
        UPDATE profile_scenario_template
        SET del_flag = '1', update_by = #{updateBy}, update_time = CURRENT_TIMESTAMP
        WHERE template_id = #{templateId} AND tenant_id = #{tenantId} AND del_flag = '0'
        """)
    int softDelete(@Param("templateId") Long templateId,
                   @Param("tenantId") String tenantId,
                   @Param("updateBy") Long updateBy);
}
