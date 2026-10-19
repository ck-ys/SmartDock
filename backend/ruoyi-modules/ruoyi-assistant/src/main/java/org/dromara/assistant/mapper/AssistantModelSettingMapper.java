package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.dromara.assistant.domain.DcsAssistantModelSetting;

/**
 * 助手模型设置 Mapper（批3）。
 * 类级 {@code @InterceptorIgnore(tenantLine="true")}：流式/异步线程无租户上下文，
 * 全部谓词显式（AssistantConfigMapper 同款口径）。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantModelSettingMapper extends BaseMapper<DcsAssistantModelSetting> {

    /**
     * 每租户一行 upsert（幂等）。service 层保证全字段非空合入（部分提交语义），
     * SQL 侧 COALESCE 兜底 DDL 默认。
     * P2 修复（2026-10-09 Q3）：补审计列——首插记 create_dept/create_by，更新记 update_by；
     * 操作人由 service 从登录上下文盖章（无登录上下文=测试/后台线程，落 null 不炸）。
     * 批1015c Q1：backend_default_config_id 同款 COALESCE（null=保持原值，0=显式清空回跟随接口默认）。
     * 业务反馈 #14 追加 Q6-A（2026-10-16）：backend_thinking_tier（null=保持，空串=显式清空）
     * 与 backend_max_tokens（null=保持，0=显式不限制）同款部分提交口径。
     */
    @Insert("INSERT INTO dcs_assistant_model_setting (id, tenant_id, assistant_default_config_id,"
        + " backend_default_config_id,"
        + " allow_user_switch, switch_scope,"
        + " backend_thinking_tier, backend_max_tokens,"
        + " create_dept, create_by, create_time, update_by, update_time)"
        + " VALUES (#{s.id}, #{s.tenantId}, #{s.assistantDefaultConfigId},"
        + " #{s.backendDefaultConfigId},"
        + " COALESCE(#{s.allowUserSwitch}, '0'), #{s.switchScope},"
        + " #{s.backendThinkingTier}, #{s.backendMaxTokens},"
        + " #{s.createDept}, #{s.createBy}, CURRENT_TIMESTAMP, #{s.updateBy}, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (tenant_id) DO UPDATE SET"
        + " assistant_default_config_id = EXCLUDED.assistant_default_config_id,"
        + " backend_default_config_id = COALESCE(EXCLUDED.backend_default_config_id, dcs_assistant_model_setting.backend_default_config_id),"
        + " allow_user_switch = COALESCE(EXCLUDED.allow_user_switch, dcs_assistant_model_setting.allow_user_switch),"
        // 批8-3 回修（dsflash P1-2）：null=保持原值——部分提交（只改默认指向）不得抹掉已配 scope；
        // 显式清空走空串（''非null 直存），与 allow_user_switch 同款 COALESCE 口径
        + " switch_scope = COALESCE(EXCLUDED.switch_scope, dcs_assistant_model_setting.switch_scope),"
        + " backend_thinking_tier = COALESCE(EXCLUDED.backend_thinking_tier, dcs_assistant_model_setting.backend_thinking_tier),"
        + " backend_max_tokens = COALESCE(EXCLUDED.backend_max_tokens, dcs_assistant_model_setting.backend_max_tokens),"
        + " update_by = EXCLUDED.update_by,"
        + " update_time = CURRENT_TIMESTAMP")
    int upsert(@Param("s") DcsAssistantModelSetting setting);
}
