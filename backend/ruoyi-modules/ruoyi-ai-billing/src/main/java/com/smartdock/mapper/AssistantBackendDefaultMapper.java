package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 助手模型设置「后台 AI 默认」槽只读小 mapper（业务反馈批1015c Q1，拍板A）。
 *
 * <p>表 dcs_assistant_model_setting 归 ruoyi-assistant 模块管写；ai-billing 的
 * AiGatewayImpl（后台链=画像诊断/字段生成/评分/规则推荐）只读 backend_default_config_id
 * 一列，不能反向依赖 assistant 模块——跨模块直查表（AiUsageAnalysisMapper 同款
 * 只读姿势）。类级 @InterceptorIgnore：MQ/后台线程无租户上下文，谓词显式带 tenant_id。</p>
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantBackendDefaultMapper {

    /** 后台默认槽值（每租户一行）；无行/空槽/0=未设（调用方回退接口默认★→最近可用） */
    @Select("SELECT backend_default_config_id FROM dcs_assistant_model_setting"
        + " WHERE tenant_id = #{tenantId} AND del_flag = '0' LIMIT 1")
    Long findBackendDefault(@Param("tenantId") String tenantId);

    /**
     * 后台槽调用参数（业务反馈 #14 追加 Q6-A，2026-10-16）：槽指向 + 思考档 + 最大输出。
     * 槽与参数一体读——网关只在"实际解析到的模型 == 槽指向"时才应用参数（槽失效静默
     * 回退 ★ 的行不吃旧槽参数，防档位错配到回退模型）。
     */
    @Select("SELECT backend_default_config_id AS backendConfigId, backend_thinking_tier AS thinkingTier,"
        + " backend_max_tokens AS maxTokens FROM dcs_assistant_model_setting"
        + " WHERE tenant_id = #{tenantId} AND del_flag = '0' LIMIT 1")
    BackendCallParams findBackendCallParams(@Param("tenantId") String tenantId);

    /** 后台槽参数行（无行=null；tier 空串/null=未启用；maxTokens null=沿用配置行, 0=显式不限制） */
    record BackendCallParams(Long backendConfigId, String thinkingTier, Integer maxTokens) {
    }
}
