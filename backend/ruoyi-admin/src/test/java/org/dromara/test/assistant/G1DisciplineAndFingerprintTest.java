package org.dromara.test.assistant;

import org.dromara.assistant.core.AssistantModelChoiceHolder;
import org.dromara.assistant.core.PromptAssets;
import org.dromara.assistant.core.ToolRegistry;
import org.dromara.assistant.service.AssistantConfigService;
import org.dromara.assistant.tools.ToolIdentity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G1（目标模式批3）纯函数单测：多工具 spec 聚合最严（遗留#1）、纪律段工具行裁剪
 * （遗留#4）、租户装配面指纹（遗留#3）。
 */
@Tag("local")
class G1DisciplineAndFingerprintTest {

    // ===== 遗留#1：effectiveMinRole 多工具聚合 =====

    @Test
    void multiToolSpecAggregatesStrictestNotFirstHit() {
        ToolRegistry.ToolSpec multi = new ToolRegistry.ToolSpec(
            java.util.Set.of("mt_a", "mt_b"), null, 0, null, null);
        // 两名不同档位：聚合取最严（admin），与 Set 迭代序无关
        Map<String, String> gate = Map.of("mt_a", "member", "mt_b", "admin");
        assertEquals(ToolIdentity.LEVEL_ADMIN, ToolRegistry.effectiveMinRole(multi, gate));
        // 交换值同样 admin——顺序无关
        Map<String, String> gateSwapped = Map.of("mt_a", "admin", "mt_b", "member");
        assertEquals(ToolIdentity.LEVEL_ADMIN, ToolRegistry.effectiveMinRole(multi, gateSwapped));
    }

    @Test
    void anyOffShutsWholeSpecEvenIfOtherMember() {
        ToolRegistry.ToolSpec multi = new ToolRegistry.ToolSpec(
            java.util.Set.of("mt_a", "mt_b"), null, 0, null, null);
        assertEquals(-1, ToolRegistry.effectiveMinRole(multi, Map.of("mt_a", "member", "mt_b", "off")));
        assertEquals(-1, ToolRegistry.effectiveMinRole(multi, Map.of("mt_b", "off")));
    }

    @Test
    void builtinAdminFloorNotLoweredByAggregation() {
        ToolRegistry.ToolSpec adminFloor = new ToolRegistry.ToolSpec(
            java.util.Set.of("mt_x", "mt_y"), null, ToolIdentity.LEVEL_ADMIN, null, null);
        // 全 member：内置管理员门槛仍在（只升不降）
        assertEquals(ToolIdentity.LEVEL_ADMIN,
            ToolRegistry.effectiveMinRole(adminFloor, Map.of("mt_x", "member", "mt_y", "member")));
    }

    // ===== 遗留#4：filterDiscipline 纪律行裁剪 =====

    @Test
    void disciplineLineDroppedWhenToolUnregistered() {
        String sys = "【行为边界】\n"
            + "7. 用户在确认环节选择取消时，简短回复已取消即可。\n"
            + "8. 用户提到附件时先调 read_attachment 再按清单读正文作答。\n"
            + "9. 先出计划再动手（三条触发，命中任一即调 submit_plan 展示计划卡）。\n"
            + "10. 执行多步任务时用 todo_write 维护任务清单。\n";
        String out = PromptAssets.filterDiscipline(sys, Set.of("todo_write"));
        assertFalse(out.contains("read_attachment"), "未注册的 read_attachment 纪律行应删除");
        assertFalse(out.contains("submit_plan"), "未注册的 submit_plan 纪律行应删除");
        assertTrue(out.contains("todo_write"), "已注册的 todo_write 纪律行应保留");
        assertTrue(out.contains("已取消即可"), "不含工具名的行不裁");
        assertEquals(2, out.split("\n").length - 1, "正文只剩表头+1 行纪律");
    }

    @Test
    void disciplineNoopWhenAllRegisteredOrNullAndTrimsAllWhenEmpty() {
        String sys = "8. 用户提到附件时先调 read_attachment。\n";
        assertEquals(sys, PromptAssets.filterDiscipline(sys,
            Set.of("read_attachment", "submit_plan", "todo_write", "save_memory")));
        // 三审回修（dspro P2-5）后语义：空注册集=工具全被闸关=权威结果，纪律行全裁；
        // 只留 null（未给注册面）为不裁
        String trimmed = PromptAssets.filterDiscipline(sys, java.util.Set.of());
        assertFalse(trimmed.contains("read_attachment"));
        assertFalse(trimmed.contains("save_memory"));
        org.junit.jupiter.api.Assertions.assertNull(PromptAssets.filterDiscipline(null, null));
    }

    // ===== 遗留#3：装配面指纹 =====

    private static AssistantConfigService.EffectiveConfig cfg(Map<String, String> gate, String mem, int comp) {
        return new AssistantConfigService.EffectiveConfig(true, true, 50,
            java.math.BigDecimal.ZERO, mem, comp, 20, "0", "小助手", gate);
    }

    @Test
    void assemblyFingerprintStableAcrossGateKeyOrder() {
        Map<String, String> a = new HashMap<>();
        a.put("x", "off");
        a.put("y", "member");
        Map<String, String> b = new HashMap<>();
        b.put("y", "member");
        b.put("x", "off");
        int trigger = org.dromara.assistant.core.AssistantCompactionPolicy.deriveTriggerTokens(128_000, 32_000);
        assertEquals(AssistantModelChoiceHolder.assemblyFingerprint(cfg(a, "1", 60000), trigger, true),
            AssistantModelChoiceHolder.assemblyFingerprint(cfg(b, "1", 60000), trigger, true),
            "toolGate 键序不得影响指纹（HashMap 序不定）");
    }

    @Test
    void configRebuildTriggersOnlyOnChange() {
        int trigger = org.dromara.assistant.core.AssistantCompactionPolicy.deriveTriggerTokens(128_000, 32_000);
        String f1 = AssistantModelChoiceHolder.assemblyFingerprint(cfg(Map.of("t", "off"), "1", 60000), trigger, true);
        AssistantModelChoiceHolder.markConfigBuilt("g1-test-thread", f1);
        assertFalse(AssistantModelChoiceHolder.needsConfigRebuild("g1-test-thread", f1), "同指纹不重建");
        String f2 = AssistantModelChoiceHolder.assemblyFingerprint(cfg(Map.of("t", "member"), "1", 60000), trigger, true);
        assertTrue(AssistantModelChoiceHolder.needsConfigRebuild("g1-test-thread", f2), "改档位即重建");
        // 批A-1：压缩触发线改按模型窗口派生——换模型导致触发线变，同样必须重建
        String f3 = AssistantModelChoiceHolder.assemblyFingerprint(cfg(Map.of("t", "off"), "1", 60000), 90_000, true);
        assertTrue(AssistantModelChoiceHolder.needsConfigRebuild("g1-test-thread", f3), "触发线变化即重建");
        // N3：本租户搜索 Key 从无到有 → 装配面变化，缓存会话必须重建才能挂上 web_search
        String f4 = AssistantModelChoiceHolder.assemblyFingerprint(cfg(Map.of("t", "off"), "1", 60000), trigger, false);
        assertTrue(AssistantModelChoiceHolder.needsConfigRebuild("g1-test-thread", f4), "搜索 Key 可用性变化即重建");
        assertFalse(AssistantModelChoiceHolder.needsConfigRebuild("g1-other-thread", f1), "未建线程不算失效");
        assertFalse(AssistantModelChoiceHolder.needsConfigRebuild(null, f1), "null 线程安全返回 false");
    }

    /** G2 三审回修（dspro P1-2）：可用行签名段——星标切换/行停用必须令指纹失配（看 A 跑 B 修复） */
    @Test
    void defaultAwareFingerprintIncludesUsableSignature() {
        java.util.Set<Long> scope = java.util.Set.of(1L, 2L);
        // 同 scope 同默认：签名一致=同指纹；星标从 1 号换到 2 号=签名变=指纹变
        String sigA = "1:Y;2:N";
        String sigB = "1:N;2:Y";
        assertEquals(AssistantModelChoiceHolder.defaultAwareFingerprint(scope, null, sigA),
            AssistantModelChoiceHolder.defaultAwareFingerprint(scope, null, sigA),
            "同签名同指纹");
        org.junit.jupiter.api.Assertions.assertNotEquals(
            AssistantModelChoiceHolder.defaultAwareFingerprint(scope, null, sigA),
            AssistantModelChoiceHolder.defaultAwareFingerprint(scope, null, sigB),
            "星标切换（默认 ID 未变）必须令指纹失配——缓存 agent 失效重建");
        org.junit.jupiter.api.Assertions.assertNotEquals(
            AssistantModelChoiceHolder.defaultAwareFingerprint(scope, null, sigA),
            AssistantModelChoiceHolder.defaultAwareFingerprint(scope, null, ""),
            "行停用（可用集缩水=签名变）必须令指纹失配");
    }
}
