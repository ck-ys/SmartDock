package org.dromara.assistant.automation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.automation.mapper.DcsAutomationMapper;
import org.springframework.stereotype.Component;

/**
 * 连续失败计数与自动停用（2026-11-05 拍板 题6 甲）。
 *
 * <p>独立小组件是为了避开 service ↔ runner 的循环依赖：执行器（runner）与扫描收口
 * （service）都要记失败，两条路径共用同一份口径。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutomationFailurePolicy {

    private final DcsAutomationMapper automationMapper;

    /**
     * 失败次数 +1；达到 {@link AutomationSchedule#FAILURE_LIMIT} 自动停用。
     *
     * @return 新的连续失败次数（0=计数失败，不影响主流程）
     */
    public int record(String tenantId, Long automationId) {
        Integer n = automationMapper.incrementFailures(tenantId, automationId);
        if (n == null) {
            return 0;
        }
        if (n >= AutomationSchedule.FAILURE_LIMIT) {
            automationMapper.disable(tenantId, automationId);
            log.warn("[Automation] 连续失败 {} 次，已自动停用 automation={}", n, automationId);
        }
        return n;
    }
}
