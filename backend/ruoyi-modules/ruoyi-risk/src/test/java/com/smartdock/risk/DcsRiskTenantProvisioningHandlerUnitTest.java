package com.smartdock.risk;

import com.smartdock.risk.domain.DcsRiskRule;
import com.smartdock.risk.mapper.DcsRiskRuleMapper;
import com.smartdock.risk.service.DcsRiskTenantProvisioningHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class DcsRiskTenantProvisioningHandlerUnitTest {

    @Test
    void shouldCloneDefaultTenantRulesForNewTenant() {
        DcsRiskRuleMapper mapper = mock(DcsRiskRuleMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
            rule(101L, "SENSITIVE_WORD", "EVENT", 2, 60),
            rule(102L, "REPORTED", "EVENT", 5, 1440),
            rule(103L, "LOGIN_FAIL", "EVENT", 3, 60),
            rule(104L, "HIGH_FREQ_API", "EVENT", 4, 5),
            rule(105L, "UPLOAD_ABUSE", "EVENT", 6, 60),
            rule(106L, "GLOBAL", "GLOBAL", 0, 60)
        ));
        when(mapper.insertBatch(any())).thenReturn(true);

        new DcsRiskTenantProvisioningHandler(mapper).provision("613419");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DcsRiskRule>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertBatch(captor.capture());
        List<DcsRiskRule> inserted = captor.getValue();

        assertThat(inserted).hasSize(6);
        assertThat(inserted).allSatisfy(rule -> {
            assertThat(rule.getRuleId()).isNull();
            assertThat(rule.getTenantId()).isEqualTo("613419");
            assertThat(rule.getCreateBy()).isNull();
            assertThat(rule.getCreateTime()).isNull();
            assertThat(rule.getUpdateBy()).isNull();
            assertThat(rule.getUpdateTime()).isNull();
        });
        assertThat(inserted)
            .extracting(DcsRiskRule::getEventType, DcsRiskRule::getRuleScope)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("SENSITIVE_WORD", "EVENT"),
                org.assertj.core.groups.Tuple.tuple("REPORTED", "EVENT"),
                org.assertj.core.groups.Tuple.tuple("LOGIN_FAIL", "EVENT"),
                org.assertj.core.groups.Tuple.tuple("HIGH_FREQ_API", "EVENT"),
                org.assertj.core.groups.Tuple.tuple("UPLOAD_ABUSE", "EVENT"),
                org.assertj.core.groups.Tuple.tuple("GLOBAL", "GLOBAL")
            );
    }

    @Test
    void shouldFailClosedWhenDefaultTenantHasNoRiskBaseline() {
        DcsRiskRuleMapper mapper = mock(DcsRiskRuleMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> new DcsRiskTenantProvisioningHandler(mapper).provision("613419"))
            .hasMessageContaining("默认租户未配置风控规则");
    }

    @Test
    void shouldFailTenantCreationWhenRiskBaselineCannotBeInserted() {
        DcsRiskRuleMapper mapper = mock(DcsRiskRuleMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
            rule(106L, "GLOBAL", "GLOBAL", 0, 60)
        ));
        when(mapper.insertBatch(any())).thenReturn(false);

        assertThatThrownBy(() -> new DcsRiskTenantProvisioningHandler(mapper).provision("613419"))
            .hasMessageContaining("新租户风控规则初始化失败");
    }

    private DcsRiskRule rule(Long id, String eventType, String scope, int weight, int windowMinutes) {
        DcsRiskRule rule = new DcsRiskRule();
        rule.setRuleId(id);
        rule.setTenantId("000000");
        rule.setEventType(eventType);
        rule.setRuleScope(scope);
        rule.setWeight(weight);
        rule.setEnabled("1");
        rule.setThresholdL1(20);
        rule.setThresholdL2(40);
        rule.setThresholdL3(60);
        rule.setThresholdL4(80);
        rule.setWindowMinutes(windowMinutes);
        rule.setDescription(eventType);
        return rule;
    }
}
