package com.smartdock.risk.service;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 风控处置策略类，负责将风险等级 L0-L4 稳定映射为可逆的线上处置动作
 * (actionType + 持续时间 duration)，并对处置/复核的入参做边界校验。
 * <p>
 * 当前内置策略:
 * <ul>
 *   <li>L4 (最高危): TEMP_FREEZE 临时冻结，持续 60 分钟</li>
 *   <li>L3 (高危): ENHANCED_VERIFICATION 增强验证，持续 30 分钟</li>
 *   <li>L0/L1/L2: 不触发任何处置，返回 {@code null}</li>
 * </ul>
 * 该类是处置动作与上层评分逻辑之间的唯一边界，调整处置策略只需修改此处。
 *
 * @author smartdock
 */
@Component
public class RiskActionPolicy {
    private static final Set<String> CONCLUSIONS = Set.of("CONFIRMED", "DISMISSED");

    /**
     * 根据风险等级推导对应的处置动作与持续时间。
     * <ul>
     *   <li>L4 → TEMP_FREEZE 临时冻结 60 分钟</li>
     *   <li>L3 → ENHANCED_VERIFICATION 增强验证 30 分钟</li>
     *   <li>低于 L3 返回 {@code null}，表示不触发任何处置</li>
     * </ul>
     *
     * @param level 风险等级 (取自 RiskScoreService 中的 LEVEL_L3 / LEVEL_L4 等常量)
     * @return 处置决策 (actionType + duration)；无需处置时返回 {@code null}
     */
    public Decision decision(int level) {
        if (level >= RiskScoreService.LEVEL_L4) {
            return new Decision("TEMP_FREEZE", Duration.ofMinutes(60));
        }
        if (level >= RiskScoreService.LEVEL_L3) {
            return new Decision("ENHANCED_VERIFICATION", Duration.ofMinutes(30));
        }
        return null;
    }

    public String requireReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) throw new ServiceException("操作理由不能为空");
        if (normalized.length() > 500) throw new ServiceException("操作理由不能超过500字");
        return normalized;
    }

    public String requireConclusion(String conclusion) {
        String normalized = conclusion == null ? "" : conclusion.trim().toUpperCase(Locale.ROOT);
        if (!CONCLUSIONS.contains(normalized)) throw new ServiceException("复核结论仅支持 CONFIRMED 或 DISMISSED");
        return normalized;
    }

    public record Decision(String actionType, Duration duration) {
    }
}
