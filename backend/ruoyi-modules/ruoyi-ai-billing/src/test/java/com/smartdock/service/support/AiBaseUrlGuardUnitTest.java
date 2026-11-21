package com.smartdock.service.support;

import com.smartdock.enums.AiProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ai-config probe 端点 SSRF 防御校验 (B1, 见 document/DCS_AUDIT_2026_06_22.md)。
 *
 * <p>背景: 早期 probe 端点接受前端任意 baseUrl 直接发起 HTTP 请求, 攻击者可让服务器
 * 探测内网/云元数据/投递 API Key。修复: 必须命中预置 provider 的域名白名单 + 禁内网。</p>
 */
class AiBaseUrlGuardUnitTest {

    @Test
    @Tag("dev")
    void allowsPresetBaseUrlExact() {
        // 精确匹配 DeepSeek 预置 baseUrl, 通过
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.DEEPSEEK, "https://api.deepseek.com/v1")).isTrue();
    }

    @Test
    @Tag("dev")
    void allowsPresetHostDifferentPath() {
        // host 相同(api.deepseek.com), 路径不同, 通过(允许版本路径差异)
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.DEEPSEEK, "https://api.deepseek.com/v2")).isTrue();
    }

    @Test
    @Tag("dev")
    void rejectsInternalIpLan() {
        // 内网 IP SSRF 攻击, 拒绝(即使 host 不校验也必须拒)
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.DEEPSEEK, "http://192.0.2.10:8080")).isFalse();
    }

    @Test
    @Tag("dev")
    void rejectsInternalIp10() {
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.DEEPSEEK, "http://10.9.0.1/admin")).isFalse();
    }

    @Test
    @Tag("dev")
    void rejectsLocalhost() {
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.DEEPSEEK, "http://127.0.0.1:8080")).isFalse();
    }

    @Test
    @Tag("dev")
    void rejectsCloudMetadata() {
        // AWS/云元数据地址, 拒绝
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.DEEPSEEK, "http://169.254.169.254/latest/meta-data/")).isFalse();
    }

    @Test
    @Tag("dev")
    void rejectsHostMismatch() {
        // provider 是 DeepSeek 但 baseUrl 指向攻击者服务器, 拒绝
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.DEEPSEEK, "https://attacker.example.com/v1")).isFalse();
    }

    @Test
    @Tag("dev")
    void rejectsNullBaseUrl() {
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.DEEPSEEK, null)).isFalse();
    }

    @Test
    @Tag("dev")
    void rejectsNonHttps() {
        // DeepSeek 预置是 https, 用户传 http 同名 host 仍拒绝(降级攻击)
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.DEEPSEEK, "http://api.deepseek.com/v1")).isFalse();
    }

    @Test
    @Tag("dev")
    void allowsAllPresetProvidersDefaultUrls() {
        // 5 个预置 provider 的默认 baseUrl 全部通过
        for (AiProvider p : AiProvider.values()) {
            if (p == AiProvider.CUSTOM) {
                continue;
            }
            assertThat(AiProviderProbeClient.isBaseUrlAllowed(p, p.getBaseUrl()))
                .as("provider %s 默认 baseUrl 应通过", p.name())
                .isTrue();
        }
    }

    // ===== OPENCODE 豁免（2026-10-16 客户机实锤）：部署形态=自建内网 Go 网关 =====

    @Test
    @Tag("dev")
    void opencodeExemptAllowsHttpIntranetGateway() {
        // 自建网关常态形态：http + 内网 IP + 端口，豁免放行（预置云端地址仅参考值）
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.OPENCODE, "http://192.0.2.11:4314/v1")).isTrue();
    }

    @Test
    @Tag("dev")
    void opencodeExemptStillAllowsCloudRelay() {
        // 云端中转（本地/12机现用形态）继续放行
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.OPENCODE, "https://opencode.ai/zen/go/v1")).isTrue();
    }

    @Test
    @Tag("dev")
    void opencodeExemptRejectsGarbage() {
        // 豁免≠不校验：空 scheme/host 的非法 URL 仍拒绝
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.OPENCODE, "ftp://x")).isFalse();
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.OPENCODE, "http://")).isFalse();
    }

    @Test
    @Tag("dev")
    void otherProvidersNotAffectedByOpencodeExempt() {
        // 豁免仅限 OPENCODE：其它厂商内网 IP 依旧拒绝（SSRF 防御不回退）
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.DEEPSEEK, "http://192.0.2.11:4314/v1")).isFalse();
        assertThat(AiProviderProbeClient.isBaseUrlAllowed(
            AiProvider.OPENROUTER, "http://10.9.0.5/v1")).isFalse();
    }
}
