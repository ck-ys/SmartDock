package com.smartdock.risk.service;

import org.dromara.common.core.exception.ServiceException;
import com.smartdock.risk.domain.DcsRiskRule;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Backend-authoritative invariants for editable security risk rules.
 */
@Component
public class RiskRulePolicy {

    public static final String SCOPE_EVENT = "EVENT";
    public static final String SCOPE_GLOBAL = "GLOBAL";
    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    public void prepareCreate(DcsRiskRule rule) {
        if (SCOPE_GLOBAL.equals(rule.getRuleScope())) {
            throw new ServiceException("全局规则由系统初始化，不能手工新增");
        }
        rule.setRuleId(null);
        clearClientControlledFields(rule);
        rule.setRuleScope(SCOPE_EVENT);
        validate(rule);
    }

    public void prepareUpdate(DcsRiskRule rule, DcsRiskRule existing) {
        if (existing == null) {
            throw new ServiceException("风控规则不存在");
        }
        clearClientControlledFields(rule);
        rule.setEventType(existing.getEventType());
        rule.setRuleScope(existing.getRuleScope());
        if (SCOPE_GLOBAL.equals(existing.getRuleScope())) {
            rule.setWeight(0);
        }
        validate(rule);
    }

    public void assertDeletable(DcsRiskRule rule) {
        if (rule == null) {
            throw new ServiceException("风控规则不存在");
        }
        if (SCOPE_GLOBAL.equals(rule.getRuleScope())) {
            throw new ServiceException("全局聚合规则不能删除");
        }
    }

    private void validate(DcsRiskRule rule) {
        if (rule.getEventType() == null || !EVENT_TYPE_PATTERN.matcher(rule.getEventType()).matches()) {
            throw new ServiceException("事件类型必须由大写字母、数字或下划线组成，且不超过 40 个字符");
        }
        if (rule.getDescription() != null && rule.getDescription().length() > 200) {
            throw new ServiceException("规则说明不能超过 200 个字符");
        }
        if (rule.getWindowMinutes() == null || rule.getWindowMinutes() < 1 || rule.getWindowMinutes() > 10080) {
            throw new ServiceException("滑动窗口必须在 1 到 10080 分钟之间");
        }
        if (!validThreshold(rule.getThresholdL1())
            || !validThreshold(rule.getThresholdL2())
            || !validThreshold(rule.getThresholdL3())
            || !validThreshold(rule.getThresholdL4())
            || !(rule.getThresholdL1() < rule.getThresholdL2()
            && rule.getThresholdL2() < rule.getThresholdL3()
            && rule.getThresholdL3() < rule.getThresholdL4())) {
            throw new ServiceException("风险阈值必须满足 1 <= L1 < L2 < L3 < L4 <= 100");
        }
        if (SCOPE_EVENT.equals(rule.getRuleScope())
            && (rule.getWeight() == null || rule.getWeight() < 1 || rule.getWeight() > 20)) {
            throw new ServiceException("事件规则单次分值必须在 1 到 20 之间");
        }
        if (!"0".equals(rule.getEnabled()) && !"1".equals(rule.getEnabled())) {
            throw new ServiceException("启用状态只能是 0 或 1");
        }
    }

    private boolean validThreshold(Integer value) {
        return value != null && value >= 1 && value <= 100;
    }

    private void clearClientControlledFields(DcsRiskRule rule) {
        rule.setTenantId(null);
        rule.setCreateDept(null);
        rule.setCreateBy(null);
        rule.setCreateTime(null);
        rule.setUpdateBy(null);
        rule.setUpdateTime(null);
    }
}
