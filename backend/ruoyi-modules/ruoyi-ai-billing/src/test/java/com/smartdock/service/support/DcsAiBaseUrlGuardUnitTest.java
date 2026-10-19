package com.smartdock.service.support;

import com.smartdock.enums.DcsAiProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ai-config probe 端点 SSRF 防御校验 (B1, 见 document/DCS_AUDIT_2026_06_22.md)。
 *
 * <p>背景: 早期 probe 端点接受前端任意 baseUrl 直接发起 HTTP 请求, 攻击者可让服务器
 * 探测内网/云元数据/投递 API Key。修复: 必须命中预置 provider 的域名白名单 + 禁内网。</p>
 */
class DcsAiBaseUrlGuardUnitTest {

    @Test
    @Tag("dev")
    void allowsPresetBaseUrlExact() {
        // 精确匹配 DeepSeek 预置 baseUrl, 通过
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.DEEPSEEK, "https://api.deepseek.com/v1")).isTrue();
    }

    @Test
    @Tag("dev")
    void allowsPresetHostDifferentPath() {
        // host 相同(api.deepseek.com), 路径不同, 通过(允许版本路径差异)
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.DEEPSEEK, "https://api.deepseek.com/v2")).isTrue();
    }

    @Test
    @Tag("dev")
    void rejectsInternalIpLan() {
        // 内网 IP SSRF 攻击, 拒绝(即使 host 不校验也必须拒)
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.DEEPSEEK, "http://192.168.50.10:8080")).isFalse();
    }

    @Test
    @Tag("dev")
    void rejectsInternalIp10() {
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.DEEPSEEK, "http://10.9.0.1/admin")).isFalse();
    }

    @Test
    @Tag("dev")
    void rejectsLocalhost() {
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.DEEPSEEK, "http://127.0.0.1:8080")).isFalse();
    }

    @Test
    @Tag("dev")
    void rejectsCloudMetadata() {
        // AWS/云元数据地址, 拒绝
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.DEEPSEEK, "http://169.254.169.254/latest/meta-data/")).isFalse();
    }

    @Test
    @Tag("dev")
    void rejectsHostMismatch() {
        // provider 是 DeepSeek 但 baseUrl 指向攻击者服务器, 拒绝
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.DEEPSEEK, "https://attacker.example.com/v1")).isFalse();
    }

    @Test
    @Tag("dev")
    void rejectsNullBaseUrl() {
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.DEEPSEEK, null)).isFalse();
    }

    @Test
    @Tag("dev")
    void rejectsNonHttps() {
        // DeepSeek 预置是 https, 用户传 http 同名 host 仍拒绝(降级攻击)
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.DEEPSEEK, "http://api.deepseek.com/v1")).isFalse();
    }

    @Test
    @Tag("dev")
    void allowsAllPresetProvidersDefaultUrls() {
        // 5 个预置 provider 的默认 baseUrl 全部通过
        for (DcsAiProvider p : DcsAiProvider.values()) {
            if (p == DcsAiProvider.CUSTOM) {
                continue;
            }
            assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(p, p.getBaseUrl()))
                .as("provider %s 默认 baseUrl 应通过", p.name())
                .isTrue();
        }
    }

    // ===== OPENCODE 豁免（2026-10-16 客户机实锤）：部署形态=自建内网 Go 网关 =====

    @Test
    @Tag("dev")
    void opencodeExemptAllowsHttpIntranetGateway() {
        // 自建网关常态形态：http + 内网 IP + 端口，豁免放行（预置云端地址仅参考值）
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.OPENCODE, "http://192.168.50.11:4314/v1")).isTrue();
    }

    @Test
    @Tag("dev")
    void opencodeExemptStillAllowsCloudRelay() {
        // 云端中转（本地/12机现用形态）继续放行
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.OPENCODE, "https://opencode.ai/zen/go/v1")).isTrue();
    }

    @Test
    @Tag("dev")
    void opencodeExemptRejectsGarbage() {
        // 豁免≠不校验：空 scheme/host 的非法 URL 仍拒绝
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.OPENCODE, "ftp://x")).isFalse();
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.OPENCODE, "http://")).isFalse();
    }

    @Test
    @Tag("dev")
    void otherProvidersNotAffectedByOpencodeExempt() {
        // 豁免仅限 OPENCODE：其它厂商内网 IP 依旧拒绝（SSRF 防御不回退）
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.DEEPSEEK, "http://192.168.50.11:4314/v1")).isFalse();
        assertThat(DcsAiProviderProbeClient.isBaseUrlAllowed(
            DcsAiProvider.OPENROUTER, "http://10.9.0.5/v1")).isFalse();
    }
}
