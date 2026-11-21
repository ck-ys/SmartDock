package com.smartdock.risk;

import com.smartdock.risk.domain.RiskWhitelist;
import com.smartdock.risk.service.RiskWhitelistPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dev")
class RiskWhitelistPolicyUnitTest {

    private final RiskWhitelistPolicy policy = new RiskWhitelistPolicy();

    @Test
    void normalizesAndClearsClientControlledFields() {
        RiskWhitelist entry = entry(" ip ", " 192.168.1.10 ");
        entry.setWhitelistId(99L);
        entry.setTenantId("forged");
        entry.setCreateDept(91L);
        entry.setCreateBy(92L);
        entry.setCreateTime(new Date());

        policy.prepareCreate(entry);

        assertThat(entry.getWhitelistId()).isNull();
        assertThat(entry.getTenantId()).isNull();
        assertThat(entry.getCreateDept()).isNull();
        assertThat(entry.getCreateBy()).isNull();
        assertThat(entry.getCreateTime()).isNull();
        assertThat(entry.getListType()).isEqualTo("IP");
        assertThat(entry.getValue()).isEqualTo("192.168.1.10");
        assertThat(entry.getEnabled()).isEqualTo("1");
    }

    @Test
    void validatesIpAndUserValues() {
        assertThatThrownBy(() -> policy.prepareCreate(entry("IP", "999.1.1.1")))
            .hasMessageContaining("有效的 IPv4 或 IPv6");
        assertThatThrownBy(() -> policy.prepareCreate(entry("USER", "user-1")))
            .hasMessageContaining("数字用户 ID");
    }

    @Test
    void rejectsReservedDomainTypeUntilChatPolicyIsIntegrated() {
        assertThatThrownBy(() -> policy.prepareCreate(entry("DOMAIN", "example.com")))
            .hasMessageContaining("DOMAIN");
    }

    @Test
    void rejectsOversizedReasonAndInvalidEnabledFlag() {
        RiskWhitelist oversized = entry("IP", "127.0.0.1");
        oversized.setReason("x".repeat(201));
        assertThatThrownBy(() -> policy.prepareCreate(oversized))
            .hasMessageContaining("200");

        RiskWhitelist invalidEnabled = entry("USER", "9100000001");
        invalidEnabled.setEnabled("yes");
        assertThatThrownBy(() -> policy.prepareCreate(invalidEnabled))
            .hasMessageContaining("0 或 1");
    }

    private RiskWhitelist entry(String type, String value) {
        RiskWhitelist entry = new RiskWhitelist();
        entry.setListType(type);
        entry.setValue(value);
        return entry;
    }
}
