package com.smartdock.risk;

import com.smartdock.risk.domain.DcsRiskRule;
import com.smartdock.risk.service.RiskRulePolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dev")
class RiskRulePolicyUnitTest {

    private final RiskRulePolicy policy = new RiskRulePolicy();

    @Test
    void preservesGlobalIdentityAndZeroWeightOnUpdate() {
        DcsRiskRule existing = rule("GLOBAL", "GLOBAL", 0, 20, 40, 60, 80);
        DcsRiskRule update = rule("TAMPERED", "EVENT", 9, 10, 30, 50, 70);

        policy.prepareUpdate(update, existing);

        assertThat(update.getEventType()).isEqualTo("GLOBAL");
        assertThat(update.getRuleScope()).isEqualTo("GLOBAL");
        assertThat(update.getWeight()).isZero();
    }

    @Test
    void rejectsThresholdsThatAreNotStrictlyIncreasing() {
        DcsRiskRule update = rule("LOGIN_FAIL", "EVENT", 3, 20, 20, 60, 80);

        assertThatThrownBy(() -> policy.prepareUpdate(update, update))
            .hasMessageContaining("L1 < L2 < L3 < L4");
    }

    @Test
    void rejectsCreatingAnotherGlobalRule() {
        DcsRiskRule create = rule("OTHER_GLOBAL", "GLOBAL", 0, 20, 40, 60, 80);

        assertThatThrownBy(() -> policy.prepareCreate(create))
            .hasMessageContaining("全局规则由系统初始化");
    }

    @Test
    void rejectsDeletingTheGlobalRule() {
        DcsRiskRule global = rule("GLOBAL", "GLOBAL", 0, 20, 40, 60, 80);

        assertThatThrownBy(() -> policy.assertDeletable(global))
            .hasMessageContaining("全局聚合规则不能删除");
    }

    @Test
    void clearsClientControlledIdentityAndAuditFieldsOnCreate() {
        DcsRiskRule create = rule("LOGIN_FAIL", "EVENT", 3, 20, 40, 60, 80);
        create.setRuleId(99L);
        create.setTenantId("forged");
        create.setCreateDept(91L);
        create.setCreateBy(92L);
        create.setCreateTime(new Date());
        create.setUpdateBy(93L);
        create.setUpdateTime(new Date());

        policy.prepareCreate(create);

        assertThat(create.getRuleId()).isNull();
        assertThat(create.getTenantId()).isNull();
        assertThat(create.getCreateDept()).isNull();
        assertThat(create.getCreateBy()).isNull();
        assertThat(create.getCreateTime()).isNull();
        assertThat(create.getUpdateBy()).isNull();
        assertThat(create.getUpdateTime()).isNull();
    }

    @Test
    void clearsClientControlledTenantAndCreationAuditFieldsOnUpdate() {
        DcsRiskRule existing = rule("LOGIN_FAIL", "EVENT", 3, 20, 40, 60, 80);
        DcsRiskRule update = rule("TAMPERED", "GLOBAL", 4, 10, 30, 50, 70);
        update.setTenantId("forged");
        update.setCreateDept(91L);
        update.setCreateBy(92L);
        update.setCreateTime(new Date());

        policy.prepareUpdate(update, existing);

        assertThat(update.getTenantId()).isNull();
        assertThat(update.getCreateDept()).isNull();
        assertThat(update.getCreateBy()).isNull();
        assertThat(update.getCreateTime()).isNull();
    }

    @Test
    void rejectsInvalidEventTypeAndOversizedDescription() {
        DcsRiskRule invalidType = rule("login-fail", "EVENT", 3, 20, 40, 60, 80);
        assertThatThrownBy(() -> policy.prepareCreate(invalidType))
            .hasMessageContaining("大写字母");

        DcsRiskRule oversizedDescription = rule("LOGIN_FAIL", "EVENT", 3, 20, 40, 60, 80);
        oversizedDescription.setDescription("x".repeat(201));
        assertThatThrownBy(() -> policy.prepareCreate(oversizedDescription))
            .hasMessageContaining("200");
    }

    private DcsRiskRule rule(
        String eventType,
        String scope,
        int weight,
        int thresholdL1,
        int thresholdL2,
        int thresholdL3,
        int thresholdL4
    ) {
        DcsRiskRule rule = new DcsRiskRule();
        rule.setEventType(eventType);
        rule.setRuleScope(scope);
        rule.setWeight(weight);
        rule.setEnabled("1");
        rule.setThresholdL1(thresholdL1);
        rule.setThresholdL2(thresholdL2);
        rule.setThresholdL3(thresholdL3);
        rule.setThresholdL4(thresholdL4);
        rule.setWindowMinutes(60);
        return rule;
    }
}
