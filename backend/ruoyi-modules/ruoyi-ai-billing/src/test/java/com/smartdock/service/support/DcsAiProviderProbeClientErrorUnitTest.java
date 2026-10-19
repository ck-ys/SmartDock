package com.smartdock.service.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务批3-②(#13)：厂商探针 HTTP 错误人话化映射单测。
 * 裸 "provider请求失败: HTTP 401" 用户看不懂——401/410/429 须翻译成可操作的动作指引。
 */
class DcsAiProviderProbeClientErrorUnitTest {

    @Test
    @Tag("dev")
    void shouldHumanize401AsKeyInvalid() {
        String msg = DcsAiProviderProbeClient.describeHttpFailure(401);
        assertThat(msg).contains("密钥无效或未授权").contains("401");
    }

    @Test
    @Tag("dev")
    void shouldHumanize410AsEndpointGone() {
        String msg = DcsAiProviderProbeClient.describeHttpFailure(410);
        assertThat(msg).contains("已下线").contains("不支持自动查询");
    }

    @Test
    @Tag("dev")
    void shouldHumanize429AsRateLimited() {
        String msg = DcsAiProviderProbeClient.describeHttpFailure(429);
        assertThat(msg).contains("限流").contains("稍后再试");
    }

    @Test
    @Tag("dev")
    void shouldKeepStatusCodeForOtherErrors() {
        String msg = DcsAiProviderProbeClient.describeHttpFailure(503);
        assertThat(msg).contains("厂商接口异常").contains("503");
    }
}
