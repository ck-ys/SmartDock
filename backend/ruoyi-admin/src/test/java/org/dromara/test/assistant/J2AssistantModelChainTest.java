package org.dromara.test.assistant;

import org.dromara.assistant.core.AssistantModelChoiceHolder;
import org.dromara.assistant.factory.AssistantModelFactory;
import org.dromara.assistant.service.AssistantModelSettingService;
import org.dromara.common.core.exception.ServiceException;
import com.smartdock.ai.AiNotConfiguredException;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.service.IDcsAiConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 助手模型解析优先级链单测（批3 Q11，2026-10-09；纯 mock 不连库）。
 *
 * <p>判据（Review Focus ④ 空槽回退）：
 * 助手默认槽（dcs_assistant_model_setting）→ 接口默认解析器（★→最近可用）；
 * 槽空/槽失效（指向行停用、跨租户、CNY 闸不过）=静默回退接口默认，不抛不 null；
 * 链尾仍无可用行才抛 AiNotConfiguredException（禁静默回退平台侧红线）。</p>
 */
@Tag("dev")
class J2AssistantModelChainTest {

    private final IDcsAiConfigService aiConfigService = mock(IDcsAiConfigService.class);
    private final AssistantModelSettingService settingService = mock(AssistantModelSettingService.class);
    private final AssistantModelFactory factory = new AssistantModelFactory(aiConfigService, settingService);

    /** 链段1: 助手默认槽有指向且行可用 → 用它, 不查接口默认 */
    @Test
    void assistantDefaultSlotWins() {
        when(settingService.findAssistantDefaultConfigId("997301")).thenReturn(42L);
        DcsAiConfig preferred = new DcsAiConfig();
        preferred.setModelName("deepseek-v4-flash");
        when(aiConfigService.resolveConfigById("997301", 42L)).thenReturn(preferred);
        assertSame(preferred, factory.resolveConfig("997301"));
        verify(aiConfigService, never()).resolveEnabledConfig(anyString(), anyString());
    }

    /** 链段2: 槽失效(指向行停用/跨租户/闸不过) → 静默回退接口默认, 不抛 */
    @Test
    void brokenSlotFallsBackToInterfaceDefault() {
        when(settingService.findAssistantDefaultConfigId("997302")).thenReturn(43L);
        when(aiConfigService.resolveConfigById("997302", 43L))
            .thenThrow(new ServiceException("指定的 AI 模型配置已停用"));
        DcsAiConfig fallback = new DcsAiConfig();
        fallback.setModelName("longcat-2.5");
        when(aiConfigService.resolveEnabledConfig("997302", "CHAT")).thenReturn(fallback);
        assertSame(fallback, factory.resolveConfig("997302"), "槽失效必须回退接口默认而非抛错");
        verify(aiConfigService).resolveEnabledConfig("997302", "CHAT"); // 批8-3 补(m3 P1-2): 回退必真查接口默认
    }

    /** 链段3: 槽空(租户没配助手设置) → 直走接口默认, 不触发按 id 解析 */
    @Test
    void emptySlotGoesStraightToInterfaceDefault() {
        when(settingService.findAssistantDefaultConfigId("997303")).thenReturn(null);
        DcsAiConfig fallback = new DcsAiConfig();
        when(aiConfigService.resolveEnabledConfig("997303", "CHAT")).thenReturn(fallback);
        assertSame(fallback, factory.resolveConfig("997303"));
        verify(aiConfigService, never()).resolveConfigById(anyString(), anyLong());
    }

    /** 链尾: 接口默认也无可用行 → AiNotConfiguredException(助手契约异常, AGUI 层转人话) */
    @Test
    void exhaustedChainThrowsAssistantContractException() {
        when(settingService.findAssistantDefaultConfigId("997304")).thenReturn(null);
        when(aiConfigService.resolveEnabledConfig(anyString(), any()))
            .thenThrow(new ServiceException("租户 997304 未启用该用途(CHAT)的 AI 模型配置"));
        AiNotConfiguredException ex = assertThrows(AiNotConfiguredException.class,
            () -> factory.resolveConfig("997304"));
        assertEquals("租户 997304 未启用该用途(CHAT)的 AI 模型配置", ex.getMessage());
    }

    /** 槽失效+链尾也空: 失效时不得吞掉接口默认的原始报错(错误话术来自链尾) */
    @Test
    void brokenSlotFallsBackThenExhausts() {
        when(settingService.findAssistantDefaultConfigId("997305")).thenReturn(44L);
        when(aiConfigService.resolveConfigById(eq("997305"), anyLong()))
            .thenThrow(new ServiceException("指定的 AI 模型配置不存在"));
        when(aiConfigService.resolveEnabledConfig("997305", "CHAT"))
            .thenThrow(new ServiceException("租户 997305 未启用该用途(CHAT)的 AI 模型配置"));
        AiNotConfiguredException ex = assertThrows(AiNotConfiguredException.class,
            () -> factory.resolveConfig("997305"));
        assertEquals("租户 997305 未启用该用途(CHAT)的 AI 模型配置", ex.getMessage());
    }

    /* ---- 批7 Q5/Q12-④: 优先级链第零段=用户会话选择(各用例独立 threadId 防静态表串扰) ---- */

    /** 用户段: 会话有选择+开关开+行可用 → 直接用用户所选, 不再查助手默认链 */
    @Test
    void userChoiceWinsOverAssistantDefault() {
        AssistantModelChoiceHolder.set("t-b7a", 77L);
        when(settingService.allowUserSwitch("997306")).thenReturn(true);
        DcsAiConfig picked = new DcsAiConfig();
        picked.setModelName("glm-5.3");
        when(aiConfigService.resolveConfigById("997306", 77L)).thenReturn(picked);
        assertSame(picked, factory.resolveConfigWithUserChoice("997306", "t-b7a"));
        verify(settingService, never()).findAssistantDefaultConfigId(anyString());
    }

    /** 用户段闸1: 自选开关关 → 忽略用户声明, 走默认链（防绕过开关点名任意模型） */
    @Test
    void switchOffIgnoresUserChoice() {
        AssistantModelChoiceHolder.set("t-b7b", 78L);
        when(settingService.allowUserSwitch("997307")).thenReturn(false);
        when(settingService.findAssistantDefaultConfigId("997307")).thenReturn(null);
        DcsAiConfig fallback = new DcsAiConfig();
        when(aiConfigService.resolveEnabledConfig("997307", "CHAT")).thenReturn(fallback);
        assertSame(fallback, factory.resolveConfigWithUserChoice("997307", "t-b7b"));
        verify(aiConfigService, never()).resolveConfigById("997307", 78L);
    }

    /** 用户段闸2: 所选行失效(停用/下架/跨租户) → 静默回退默认链, 不打断会话 */
    @Test
    void brokenUserChoiceFallsBack() {
        AssistantModelChoiceHolder.set("t-b7c", 79L);
        when(settingService.allowUserSwitch("997308")).thenReturn(true);
        when(aiConfigService.resolveConfigById("997308", 79L))
            .thenThrow(new ServiceException("指定的 AI 模型配置已停用"));
        when(settingService.findAssistantDefaultConfigId("997308")).thenReturn(null);
        DcsAiConfig fallback = new DcsAiConfig();
        when(aiConfigService.resolveEnabledConfig("997308", "CHAT")).thenReturn(fallback);
        assertSame(fallback, factory.resolveConfigWithUserChoice("997308", "t-b7c"),
            "用户所选失效必须回退默认链而非抛错");
        verify(aiConfigService).resolveEnabledConfig("997308", "CHAT"); // 批8-3 补(m3 P1-2)
    }

    /** 用户段空: 会话没选过 → 连开关都不查, 直走默认链 */
    @Test
    void noChoiceSkipsUserSegment() {
        when(settingService.findAssistantDefaultConfigId("997309")).thenReturn(null);
        DcsAiConfig fallback = new DcsAiConfig();
        when(aiConfigService.resolveEnabledConfig("997309", "CHAT")).thenReturn(fallback);
        assertSame(fallback, factory.resolveConfigWithUserChoice("997309", "t-b7d"));
        verify(settingService, never()).allowUserSwitch(anyString());
    }

    /** Holder 解析: 数字串收/脏值(null/空串/非数字/非正数)清位; null threadId 不炸 */
    @Test
    void holderParsesDirtyValues() {
        AssistantModelChoiceHolder.set("t-b7e", "123");
        assertEquals(Long.valueOf(123L), AssistantModelChoiceHolder.get("t-b7e"));
        AssistantModelChoiceHolder.set("t-b7e", "abc");
        assertEquals(null, AssistantModelChoiceHolder.get("t-b7e"), "脏值=清选择(前端「跟随默认」上行 null 同款)");
        AssistantModelChoiceHolder.set("t-b7e", "-5");
        assertEquals(null, AssistantModelChoiceHolder.get("t-b7e"));
        assertEquals(null, AssistantModelChoiceHolder.get(null));
    }

    /** 缓存失效基准(批8-3 哨兵化语义): 未建过=无缓存不触发; 换模型/切回跟随默认都触发;
     *  回退(picked 原值记)不反复重建; markBuilt(null) 不炸(CHM 拒 null 值, dspro P0-1) */
    @Test
    void holderRebuildBenchmark() {
        AssistantModelChoiceHolder.set("t-b7f", 81L);
        assertEquals(false, AssistantModelChoiceHolder.needsRebuild("t-b7f"),
            "有选择但从未建过=无缓存可失效, 首建自然按当前声明走(哨兵化后不再误触发)");
        AssistantModelChoiceHolder.markBuilt("t-b7f", 81L);
        assertEquals(false, AssistantModelChoiceHolder.needsRebuild("t-b7f"), "建完=基准一致不重建");
        AssistantModelChoiceHolder.set("t-b7f", 82L);
        assertEquals(true, AssistantModelChoiceHolder.needsRebuild("t-b7f"), "换模型=需重建");
        // 回退场景: picked=82 建时行失效回退默认链——基准仍记 82(原值), 不每请求重建
        AssistantModelChoiceHolder.markBuilt("t-b7f", 82L);
        assertEquals(false, AssistantModelChoiceHolder.needsRebuild("t-b7f"), "回退后基准=picked 原值, 稳定不重建");
        // 批8-3 翻转(glm53 P1-1/dspro P1-2/dsflash P1-1): 切回跟随默认=真实声明变化, 需重建
        AssistantModelChoiceHolder.set("t-b7f", null);
        assertEquals(true, AssistantModelChoiceHolder.needsRebuild("t-b7f"),
            "切回跟随默认=声明变化, 须失效旧模型缓存(旧实现此处恒 false→旧模型继续计费)");
        AssistantModelChoiceHolder.markBuilt("t-b7f", null);
        assertEquals(false, AssistantModelChoiceHolder.needsRebuild("t-b7f"),
            "按默认链重建完成后基准归位(哨兵 0), 后续无声明请求不再触发");
        assertEquals(false, AssistantModelChoiceHolder.needsRebuild(null));
    }

    /** 批8-3 回归(dspro P0-1): 无 pin 会话首建 agent——markBuilt 收 null 不得 NPE(CHM 拒 null 值),
     *  且建后无声明请求(needsRebuild)恒 false(默认链用户零重建开销) */
    @Test
    void holderMarkBuiltNullNeverThrows() {
        AssistantModelChoiceHolder.markBuilt("t-b7g", null);
        assertEquals(false, AssistantModelChoiceHolder.needsRebuild("t-b7g"), "默认链建完, 无声明=不重建");
        AssistantModelChoiceHolder.set("t-b7g", 90L);
        assertEquals(true, AssistantModelChoiceHolder.needsRebuild("t-b7g"), "默认链会话中途选模型=需重建");
    }
}
