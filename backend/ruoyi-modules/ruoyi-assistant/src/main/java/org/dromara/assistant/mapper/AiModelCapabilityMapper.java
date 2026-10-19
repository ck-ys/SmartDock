package org.dromara.assistant.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.assistant.domain.DcsAiModelCapability;

import java.util.List;

/**
 * AI 模型能力目录 Mapper（2026-10-09 批1）。SEED 行跨租户共享（tenant 000000），
 * 故 @InterceptorIgnore + SQL 显式 tenant_id 谓词（红线同 {@link AssistantFavoriteMapper}）。
 * 正则匹配放服务层 Java 侧（可单测；坏正则跳过不炸）。
 */
@InterceptorIgnore(tenantLine = "true")
public interface AiModelCapabilityMapper extends BaseMapper<DcsAiModelCapability> {

    /**
     * 匹配候选行：本租户 MANUAL + 全局 SEED，provider 精确或兜底 '*'（rule_order 升序）。
     * P2 修复（2026-10-09）：000000 的共享行必须限定 source='SEED'——否则超管（租户 000000）
     * 做的 MANUAL 覆盖会经 OR 分支被所有租户读到，一次租户级操作静默变成全平台生效。
     */
    @Select("SELECT * FROM dcs_ai_model_capability WHERE del_flag='0'"
        + " AND (tenant_id=#{tenantId} OR (tenant_id='000000' AND source='SEED'))"
        + " AND (provider=#{provider} OR provider='*')"
        + " ORDER BY rule_order ASC, id ASC")
    List<DcsAiModelCapability> findCandidates(@Param("tenantId") String tenantId,
                                              @Param("provider") String provider);

    /** 恢复官方（删本租户 MANUAL 覆盖行；SEED 行不受影响） */
    @Delete("DELETE FROM dcs_ai_model_capability WHERE tenant_id=#{tenantId}"
        + " AND source='MANUAL' AND provider=#{provider} AND model_pattern=#{pattern}")
    int deleteManual(@Param("tenantId") String tenantId,
                     @Param("provider") String provider,
                     @Param("pattern") String pattern);
}
