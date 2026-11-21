package com.smartdock.service.support;

import com.smartdock.domain.vo.AiProviderTemplateVo;
import com.smartdock.enums.AiProvider;
import com.smartdock.enums.AiProviderTemplates;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N1（2026-10-23）回修：SSRF 白名单与厂商模板库同源。
 *
 * <p>背景（dspro P1-4）：模板库有国际站变体（Z.ai / moonshot.cn / dashscope-intl /
 * minimax.io / siliconflow.com），但探测白名单只认预置单域名——用户选完模板点「获取模型」
 * 会被自己的 SSRF 校验拒绝。修复后白名单=预置 host + 同厂商模板 host，攻击者域名仍拒。</p>
 */
class N1BaseUrlVariantUnitTest {

    @Test
    @Tag("local")
    void allowsInternationalTemplateHosts() {
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.ZHIPU, "https://api.z.ai/api/paas/v4")).isTrue();
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.KIMI, "https://api.moonshot.cn/v1")).isTrue();
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.QWEN, "https://dashscope-intl.aliyuncs.com/compatible-mode/v1")).isTrue();
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.MINIMAX, "https://api.minimax.io/v1")).isTrue();
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.SILICONFLOW, "https://api.siliconflow.com/v1")).isTrue();
    }

    @Test
    @Tag("local")
    void everyTemplateBaseUrlPassesItsProviderGuard() {
        for (AiProviderTemplateVo tpl : AiProviderTemplates.list()) {
            if (tpl.getProvider() == null || tpl.getBaseUrl() == null || tpl.getBaseUrl().isBlank()) {
                continue;
            }
            AiProvider provider;
            try {
                provider = AiProvider.valueOf(tpl.getProvider());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (provider == AiProvider.CUSTOM) {
                continue;
            }
            assertThat(AiProviderProbeClient.isBaseUrlAllowed(provider, tpl.getBaseUrl()))
                .as("模板 %s 的地址 %s 应通过 %s 的白名单", tpl.getTemplateId(), tpl.getBaseUrl(), provider)
                .isTrue();
        }
    }

    @Test
    @Tag("local")
    void stillRejectsForeignAndInternalHosts() {
        // 变体放行只限官方域名，攻击者域名/内网 IP 照拒
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.ZHIPU, "https://attacker.example.com/api/paas/v4")).isFalse();
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.KIMI, "http://192.0.2.10:8080/v1")).isFalse();
        // 跨厂商模板域名也不能借道（Kimi 不能指向智谱域名）
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.KIMI, "https://open.bigmodel.cn/api/paas/v4")).isFalse();
    }
}
