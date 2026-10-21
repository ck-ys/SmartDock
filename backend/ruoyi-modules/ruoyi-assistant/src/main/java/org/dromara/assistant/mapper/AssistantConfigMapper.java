package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.assistant.domain.DcsAssistantConfig;
import org.dromara.assistant.domain.vo.AssistantAdminStatsVo;

/**
 * 助手租户配置 Mapper（批G G1）。
 * 类级 {@code @InterceptorIgnore(tenantLine="true")}：流式/异步线程无租户上下文，
 * 全部谓词显式（spec §5.2 静默串租户防线；WalletReconcileMapper 同款口径）。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AssistantConfigMapper extends BaseMapper<DcsAssistantConfig> {

    /**
     * 租户覆盖行 upsert（首插/在位更新二合一，幂等）。
     * 全列 COALESCE 缺省：调用方只传要改的字段，null 列落 DDL 默认（首插）/
     * 保持原值（更新）——列全 NOT NULL，裸插 null 会违反约束（G1 实证）。
     * ⚠️ 禁止绕过 {@code AssistantConfigService.update} 直调本方法：EXCLUDED 值已被
     * INSERT 侧 COALESCE 缺省污染（如联网列缺省 20），直调会把已配值静默打回默认——
     * 部分更新的真正保护在 service 层的在位行合并（业务批3-④ dspro 注记）。
     */
    @Insert("INSERT INTO dcs_assistant_config (tenant_id, enabled, daily_round_limit_per_user,"
        + " daily_budget_amount, memory_enabled, compaction_trigger_tokens, websearch_limit_per_user, digest_enabled, assistant_name, create_time)"
        + " VALUES (#{c.tenantId}, COALESCE(#{c.enabled}, '0'), COALESCE(#{c.dailyRoundLimitPerUser}, 50),"
        + " COALESCE(#{c.dailyBudgetAmount}, 50.0000), COALESCE(#{c.memoryEnabled}, '0'),"
        + " COALESCE(#{c.compactionTriggerTokens}, 60000), COALESCE(#{c.websearchLimitPerUser}, 20),"
        + " COALESCE(#{c.digestEnabled}, '0'), #{c.assistantName}, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (tenant_id) DO UPDATE SET"
        + " enabled = COALESCE(EXCLUDED.enabled, dcs_assistant_config.enabled),"
        + " daily_round_limit_per_user = COALESCE(EXCLUDED.daily_round_limit_per_user, dcs_assistant_config.daily_round_limit_per_user),"
        + " daily_budget_amount = COALESCE(EXCLUDED.daily_budget_amount, dcs_assistant_config.daily_budget_amount),"
        + " memory_enabled = COALESCE(EXCLUDED.memory_enabled, dcs_assistant_config.memory_enabled),"
        + " compaction_trigger_tokens = COALESCE(EXCLUDED.compaction_trigger_tokens, dcs_assistant_config.compaction_trigger_tokens),"
        + " websearch_limit_per_user = COALESCE(EXCLUDED.websearch_limit_per_user, dcs_assistant_config.websearch_limit_per_user),"
        + " digest_enabled = COALESCE(EXCLUDED.digest_enabled, dcs_assistant_config.digest_enabled),"
        // 快刀批 Q6：称呼列可空且 service 层已合并出终值（含""→NULL 显式清除）——
        // 直写 EXCLUDED 不 COALESCE，否则清不掉旧称呼
        + " assistant_name = EXCLUDED.assistant_name,"
        + " update_time = CURRENT_TIMESTAMP")
    int upsert(@Param("c") DcsAssistantConfig config);

    /**
     * 批1 Q1=B：工具开关列专用 upsert（三审回修 P2——替代 select-then-insert/update，
     * 消并发首插撞主键）。首插=平台默认 + enabled='0'（与 upsert 首插口径逐列一致，
     * 保持"未配置=助手关闭"语义）；在位行只动 tool_gate（"" 哨兵直写可清空）。
     */
    @Insert("INSERT INTO dcs_assistant_config (tenant_id, enabled, daily_round_limit_per_user,"
        + " daily_budget_amount, memory_enabled, compaction_trigger_tokens, websearch_limit_per_user,"
        + " digest_enabled, tool_gate, create_time)"
        + " VALUES (#{c.tenantId}, '0', 50, 50.0000, '0', 60000, 20, '0', #{c.toolGate}, CURRENT_TIMESTAMP)"
        + " ON CONFLICT (tenant_id) DO UPDATE SET"
        + " tool_gate = EXCLUDED.tool_gate,"
        + " update_time = CURRENT_TIMESTAMP")
    int upsertToolGate(@Param("c") DcsAssistantConfig config);

    /**
     * 管理页统计头（ZCode 对齐 Q4 批C）：全租户四计数 + 本月费用单条聚合。
     * 费用谓词与预算闸（AssistantDailyQuota）逐字同源（dspro P2-2 回修：
     * del_flag='0'+biz_type='DEDUCT'，否则软删/冲正流水会让管理页与钱包对不上账）；
     * 月界用显式 Asia/Shanghai（dspro P2-3 回修：DB 会话时区非东八区时，每月前 8 小时
     * 会把本月费用算成上月合计——DcsTodoMapper 同款项目语式）。四张业务表均无软删语义，
     * 计数不叠 del_flag 过滤。
     */
    @Select("SELECT"
        + " (SELECT COUNT(*) FROM dcs_assistant_conversation WHERE tenant_id=#{tenantId}) AS conversations,"
        + " (SELECT COUNT(*) FROM dcs_assistant_message WHERE tenant_id=#{tenantId}) AS messages,"
        + " (SELECT COUNT(*) FROM dcs_assistant_action WHERE tenant_id=#{tenantId}) AS actions,"
        + " (SELECT COUNT(*) FROM dcs_assistant_feedback WHERE tenant_id=#{tenantId}) AS feedbacks,"
        + " (SELECT COALESCE(SUM(amount), 0) FROM dcs_tenant_wallet_ledger"
        + "   WHERE tenant_id=#{tenantId} AND del_flag='0' AND service_code='AI_ASSISTANT_CHAT'"
        + "   AND direction='OUT' AND biz_type='DEDUCT'"
        + "   AND create_time >= date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')) AS month_cost")
    AssistantAdminStatsVo selectAdminStats(@Param("tenantId") String tenantId);
}
