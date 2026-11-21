package com.smartdock.service.impl;

import com.smartdock.domain.AiConfig;
import com.smartdock.domain.bo.AiProbeBo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigMaskUnitTest {

    @Test
    @Tag("dev")
    void masksApiKeyMiddleWithoutReturningPlaintext() {
        String masked = AiConfigServiceImpl.maskApiKeyForDisplay("key-sdwabcdefdwa");

        assertThat(masked).isEqualTo("key-sdw****dwa");
        assertThat(masked).doesNotContain("abcdef");
    }

    @Test
    @Tag("dev")
    void savedConfigProbePayloadKeepsStoredKeyServerSide() {
        AiConfig config = new AiConfig();
        config.setProvider("DEEPSEEK");
        config.setBaseUrl("https://api.deepseek.com/v1/");
        config.setApiKey("sk-stored-secret");
        config.setModelName("deepseek-chat");
        config.setTemperature(0.3D);
        config.setTimeoutSeconds(30);

        AiProbeBo probe = AiConfigServiceImpl.toProbeBoForSavedConfig(config);

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
        AiConfig config = new AiConfig();
        config.setProvider("DEEPSEEK");
        config.setBaseUrl("https://api.deepseek.com");
        config.setApiKey("sk-stored-secret");
        config.setModelName("deepseek-chat");

        AiProbeBo override = new AiProbeBo();
        override.setModelName("deepseek-reasoner");

        AiProbeBo probe = AiConfigServiceImpl.toProbeBoForSavedConfig(config, override);

        assertThat(probe.getApiKey()).isEqualTo("sk-stored-secret");
        assertThat(probe.getModelName()).isEqualTo("deepseek-reasoner");
    }
}
