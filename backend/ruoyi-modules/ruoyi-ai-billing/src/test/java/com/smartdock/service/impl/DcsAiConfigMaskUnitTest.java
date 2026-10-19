package com.smartdock.service.impl;

import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.bo.DcsAiProbeBo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DcsAiConfigMaskUnitTest {

    @Test
    @Tag("dev")
    void masksApiKeyMiddleWithoutReturningPlaintext() {
        String masked = DcsAiConfigServiceImpl.maskApiKeyForDisplay("key-sdwabcdefdwa");

        assertThat(masked).isEqualTo("key-sdw****dwa");
        assertThat(masked).doesNotContain("abcdef");
    }

    @Test
    @Tag("dev")
    void savedConfigProbePayloadKeepsStoredKeyServerSide() {
        DcsAiConfig config = new DcsAiConfig();
        config.setProvider("DEEPSEEK");
        config.setBaseUrl("https://api.deepseek.com/v1/");
        config.setApiKey("sk-stored-secret");
        config.setModelName("deepseek-chat");
        config.setTemperature(0.3D);
        config.setTimeoutSeconds(30);

        DcsAiProbeBo probe = DcsAiConfigServiceImpl.toProbeBoForSavedConfig(config);

        assertThat(probe.getProvider()).isEqualTo("DEEPSEEK");
        assertThat(probe.getBaseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(probe.getApiKey()).isEqualTo("sk-stored-secret");
        assertThat(probe.getModelName()).isEqualTo("deepseek-chat");
        assertThat(probe.getTemperature()).isEqualTo(0.3D);
        assertThat(probe.getTimeoutSeconds()).isEqualTo(30);
    }

    @Test
    @Tag("dev")
    void savedConfigProbePayloadCanUseCurrentFormModelWithoutReplacingStoredKey() {
        DcsAiConfig config = new DcsAiConfig();
        config.setProvider("DEEPSEEK");
        config.setBaseUrl("https://api.deepseek.com");
        config.setApiKey("sk-stored-secret");
        config.setModelName("deepseek-chat");

        DcsAiProbeBo override = new DcsAiProbeBo();
        override.setModelName("deepseek-reasoner");

        DcsAiProbeBo probe = DcsAiConfigServiceImpl.toProbeBoForSavedConfig(config, override);

        assertThat(probe.getApiKey()).isEqualTo("sk-stored-secret");
        assertThat(probe.getModelName()).isEqualTo("deepseek-reasoner");
    }
}
