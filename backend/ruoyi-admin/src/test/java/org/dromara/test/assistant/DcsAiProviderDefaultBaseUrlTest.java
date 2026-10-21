package org.dromara.test.assistant;

import com.smartdock.enums.DcsAiProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 批1（2026-10-21 钥匙管理需求）纯函数单测：标准厂商空地址自动补官方默认。
 * 口径红线：预设地址必须与 migration/2026-10-21_key_mgmt_provider_default_base_url.sql
 * 逐字一致（两处漂移=存量修复与新保存行为分叉）；CUSTOM/未知厂商必须返回 null
 * （返回空串会让空地址悄悄合法化，批0 的 api.openai.com 暗改道事故复现）。
 */
@Tag("local")
class DcsAiProviderDefaultBaseUrlTest {

    @Test
    void standardProviderReturnsPresetUrl() {
        assertEquals("https://api.deepseek.com/v1", DcsAiProvider.defaultBaseUrl("DEEPSEEK"));
        assertEquals("https://openrouter.ai/api/v1", DcsAiProvider.defaultBaseUrl("OPENROUTER"));
        assertEquals("https://opencode.ai/zen/go/v1", DcsAiProvider.defaultBaseUrl("OPENCODE"));
        assertEquals("https://api.openai.com/v1", DcsAiProvider.defaultBaseUrl("OPENAI"));
    }

    @Test
    void customAndUnknownReturnNull() {
        assertNull(DcsAiProvider.defaultBaseUrl("CUSTOM"), "自定义厂商必须手填，返回 null 才会被拒");
        assertNull(DcsAiProvider.defaultBaseUrl("NO_SUCH_PROVIDER"));
        assertNull(DcsAiProvider.defaultBaseUrl(null));
    }

    @Test
    void everyNonCustomEnumHasNonBlankPreset() {
        for (DcsAiProvider p : DcsAiProvider.values()) {
            if (p == DcsAiProvider.CUSTOM) {
                continue;
            }
            assertEquals(p.getBaseUrl(), DcsAiProvider.defaultBaseUrl(p.name()),
                p.name() + " 默认地址必须与枚举一致");
            org.junit.jupiter.api.Assertions.assertFalse(p.getBaseUrl().isBlank(),
                p.name() + " 预设地址不得为空（空=保存侧补不进地址）");
        }
    }
}
