package com.smartdock.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.smartdock.domain.DcsAiModelHealth;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * AI 模型巡检健康 Mapper（AI 缺口批 Q9，2026-10-14）。
 *
 * <p>类级 {@code @InterceptorIgnore(tenantLine="true")}：调度上下文无登录租户（MP 租户插件
 * 会拿默认租户静默改写谓词导致跨租户巡检读错行），租户隔离全部由显式 {@code tenant_id}
 * 谓词自带——与 {@link WalletAlertRecipientMapper} / {@link AiUsageAnalysisMapper} 同配方。</p>
 *
 * @author smartdock
 */
@InterceptorIgnore(tenantLine = "true")
public interface DcsAiModelHealthMapper extends BaseMapper<DcsAiModelHealth> {

    /**
     * 巡检租户清单：最近 N 天有 AI 用量的租户 ∪ 配了助手模型设置的租户。
     * （无用量也无设置 = 没人在用，探活纯属花钱，跳过。）
     * glm53 P1-1 回修：排除 caller=PATROL——巡检自己写的 usage 行不算「在用」证据，
     * 否则目标集自我续命（真实用量停了巡检照跑永远停不下来）。
     */
    @Select("""
        SELECT DISTINCT tenant_id FROM dcs_ai_usage
         WHERE create_time >= #{since} AND caller <> 'PATROL'
        UNION
        SELECT DISTINCT tenant_id FROM dcs_assistant_model_setting WHERE del_flag = '0'
        """)
    List<String> patrolTenants(@Param("since") Date since);

    /**
     * 租户近 N 天真实用过的配置（按调用量降序）：探活目标来源③。
     * success 全量计入——失败调用也算「在用」（且正是要盯的）；
     * 同上排除 PATROL 自证行。
     */
    @Select("""
        SELECT config_id, COUNT(*) AS calls
          FROM dcs_ai_usage
         WHERE tenant_id = #{tenantId}
           AND create_time >= #{since}
           AND config_id IS NOT NULL
           AND caller <> 'PATROL'
         GROUP BY config_id
         ORDER BY calls DESC
        """)
    List<Map<String, Object>> recentUsedConfigs(@Param("tenantId") String tenantId, @Param("since") Date since);

    /**
     * 租户助手模型设置行（探活目标来源①②）：assistant_default_config_id + switch_scope(JSON 串)。
     * 跨模块只读 dcs_assistant_model_setting（实体归 ruoyi-assistant，巡检不引模块依赖只取两列）。
     * 别名加双引号防 PG 折叠成小写（Map 键保持 camelCase）。
     */
    @Select("""
        SELECT assistant_default_config_id AS "defaultConfigId", switch_scope AS "switchScope"
          FROM dcs_assistant_model_setting
         WHERE tenant_id = #{tenantId}
           AND del_flag = '0'
         LIMIT 1
        """)
    Map<String, Object> assistantModelSetting(@Param("tenantId") String tenantId);
}
