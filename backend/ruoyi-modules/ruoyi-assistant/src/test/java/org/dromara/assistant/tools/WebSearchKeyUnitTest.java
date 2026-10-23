package org.dromara.assistant.tools;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N3 Q10（2026-10-23 拍板 B 案）：联网搜索只认本租户自己的 key。
 *
 * <p>纯函数单测：可用性判定与「无租户上下文 = 未配置」的兜底口径。
 * 真实取 key / 出网由 E2E 覆盖。</p>
 */
@Tag("local")
class WebSearchKeyUnitTest {

    @Test
    void 可用密钥_必须是sk前缀的非停用值() {
        assertThat(WebSearchTools.isUsableKey("sk-abc123")).isTrue();
        assertThat(WebSearchTools.isUsableKey("  sk-abc123  ".trim())).isTrue();
    }

    @Test
    void 空值与停用词_一律不可用() {
        assertThat(WebSearchTools.isUsableKey("")).isFalse();
        assertThat(WebSearchTools.isUsableKey("off")).isFalse();
        assertThat(WebSearchTools.isUsableKey("OFF")).isFalse();
        assertThat(WebSearchTools.isUsableKey("disabled")).isFalse();
        assertThat(WebSearchTools.isUsableKey("Disabled")).isFalse();
    }

    @Test
    void 非博查形态的key_不可用() {
        assertThat(WebSearchTools.isUsableKey("abc123")).isFalse();
        assertThat(WebSearchTools.isUsableKey("sk-")).isTrue(); // 前缀合法，长度由上游校验
        assertThat(WebSearchTools.isUsableKey("bearer sk-abc")).isFalse();
    }

    @Test
    void 无租户上下文_注册闸关闭且不抛异常() {
        // N3 三审 P0 回归：建 agent 的异步线程拿不到租户时必须关闭（而不是回落到别人的 key）
        assertThat(WebSearchTools.shouldRegisterWebSearch(true, null, () -> "sk-abc123")).isFalse();
        assertThat(WebSearchTools.shouldRegisterWebSearch(true, "", () -> "sk-abc123")).isFalse();
        assertThat(WebSearchTools.hasUsableTenantKey(null, null)).isFalse();
    }

    @Test
    void 租户明确且开关开启_按该租户的key决定是否注册() {
        assertThat(WebSearchTools.shouldRegisterWebSearch(true, "000000", () -> "sk-abc123")).isTrue();
        assertThat(WebSearchTools.shouldRegisterWebSearch(true, "000000", () -> "off")).isFalse();
        assertThat(WebSearchTools.shouldRegisterWebSearch(true, "000000", () -> "")).isFalse();
        // 开关关 = 就算配了 key 也不注册
        assertThat(WebSearchTools.shouldRegisterWebSearch(false, "000000", () -> "sk-abc123")).isFalse();
        // 读 key 抛异常 = 失败关闭
        assertThat(WebSearchTools.shouldRegisterWebSearch(true, "000000", () -> {
            throw new IllegalStateException("db down");
        })).isFalse();
    }
}
