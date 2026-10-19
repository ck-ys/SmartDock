package com.smartdock.risk.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.TenantConstants;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.risk.domain.DcsRiskRule;
import com.smartdock.risk.mapper.DcsRiskRuleMapper;
import org.dromara.system.service.TenantProvisioningHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Provisions the security risk baseline for new tenants.
 */
@Component
@RequiredArgsConstructor
public class DcsRiskTenantProvisioningHandler implements TenantProvisioningHandler {

    private final DcsRiskRuleMapper ruleMapper;

    @Override
    public void provision(String tenantId) {
        List<DcsRiskRule> defaultRules = TenantHelper.ignore(() -> ruleMapper.selectList(
            new LambdaQueryWrapper<DcsRiskRule>()
                .eq(DcsRiskRule::getTenantId, TenantConstants.DEFAULT_TENANT_ID)
                .orderByAsc(DcsRiskRule::getRuleId)
        ));
        if (defaultRules.isEmpty()) {
            throw new ServiceException("默认租户未配置风控规则，无法创建安全基线");
        }

        List<DcsRiskRule> tenantRules = defaultRules.stream()
            .map(rule -> copyForTenant(rule, tenantId))
            .toList();
        boolean inserted = TenantHelper.ignore(() -> ruleMapper.insertBatch(tenantRules));
        if (!inserted) {
            throw new ServiceException("新租户风控规则初始化失败");
        }
    }

    private DcsRiskRule copyForTenant(DcsRiskRule source, String tenantId) {
        DcsRiskRule target = new DcsRiskRule();
        target.setTenantId(tenantId);
        target.setEventType(source.getEventType());
        target.setRuleScope(source.getRuleScope());
        target.setWeight(source.getWeight());
        target.setEnabled(source.getEnabled());
        target.setThresholdL1(source.getThresholdL1());
        target.setThresholdL2(source.getThresholdL2());
        target.setThresholdL3(source.getThresholdL3());
        target.setThresholdL4(source.getThresholdL4());
        target.setWindowMinutes(source.getWindowMinutes());
        target.setDescription(source.getDescription());
        return target;
    }
}
