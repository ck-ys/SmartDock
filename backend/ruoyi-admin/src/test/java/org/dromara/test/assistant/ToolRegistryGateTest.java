package org.dromara.test.assistant;

import org.dromara.assistant.core.ToolRegistry;
import org.dromara.assistant.service.AssistantConfigService;
import org.dromara.assistant.tools.ToolIdentity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 批1 Q1=B（2026-10-21 开关矩阵）纯函数单测：租户级工具档位单点判定 effectiveMinRole。
 * 口径红线：off=-1 彻底关闭；member/admin 只升不降（内置管理员门槛不得被租户配置拉低）；
 * 未配置/非法值/空表=内置门槛不变（老租户零迁移零行为变化）。
 */
@Tag("local")
class ToolRegistryGateTest {

    private static final ToolRegistry.ToolSpec MEMBER_TOOL = new ToolRegistry.ToolSpec(
        java.util.Set.of("gate_test_tool"), null, 0, null, null);
    private static final ToolRegistry.ToolSpec ADMIN_TOOL = new ToolRegistry.ToolSpec(
        java.util.Set.of("gate_admin_tool"), null, ToolIdentity.LEVEL_ADMIN, null, null);

    /** 三审回修（dspro P1-2）：坏 JSON fail-closed——按全工具关闭处理，已关工具不复活 */
    @Test
    void corruptedJsonFailsClosedToAllOff() {
        Map<String, String> gate = AssistantConfigService.parseToolGate("{\"web_search\": \"of");
        assertEquals(ToolRegistry.allToolNames().size(), gate.size(), "坏 JSON=全工具兜底条目");
        for (String v : gate.values()) {
            assertEquals("off", v, "兜底值一律 off（fail-closed）");
        }
        // 全 off 走判定：真实工具注册面应全部跳过（关闭语义闭环；
        // MEMBER_TOOL 是测试专用名不在 allToolNames，兜底表里没有它）
        ToolRegistry.ToolSpec webSearch = new ToolRegistry.ToolSpec(
            java.util.Set.of("web_search"), null, 0, null, null);
        assertEquals(-1, ToolRegistry.effectiveMinRole(webSearch, gate));
    }

    /** D-1#6 闭环：清空哨兵序列化不依赖全局 updateStrategy（空表→""、非空→JSON） */
    @Test
    void serializeGateEmptyMeansSentinelClear() {
        assertEquals("", AssistantConfigService.serializeGate(null));
        assertEquals("", AssistantConfigService.serializeGate(Map.of()));
        org.junit.jupiter.api.Assertions.assertTrue(
            AssistantConfigService.serializeGate(Map.of("web_search", "off")).contains("\"web_search\":\"off\""));
    }

    /** 空/空白=空表（未配置，走内置门槛）——与坏 JSON 的全关兜底区分 */
    @Test
    void blankJsonMeansNoOverride() {
        for (String raw : new String[] {null, "", "   "}) {
            assertEquals(Map.of(), AssistantConfigService.parseToolGate(raw), "空配置(" + raw + ")=空表不覆盖");
        }
        assertEquals(0, ToolRegistry.effectiveMinRole(MEMBER_TOOL, AssistantConfigService.parseToolGate(null)));
    }

    @Test
    void nullOrEmptyGateKeepsBuiltinRole() {
        assertEquals(0, ToolRegistry.effectiveMinRole(MEMBER_TOOL, null));
        assertEquals(0, ToolRegistry.effectiveMinRole(MEMBER_TOOL, Map.of()));
        assertEquals(ToolIdentity.LEVEL_ADMIN, ToolRegistry.effectiveMinRole(ADMIN_TOOL, null));
    }

    @Test
    void offClosesToolEntirely() {
        assertEquals(-1, ToolRegistry.effectiveMinRole(MEMBER_TOOL, Map.of("gate_test_tool", "off")));
        assertEquals(-1, ToolRegistry.effectiveMinRole(ADMIN_TOOL, Map.of("gate_admin_tool", "off")));
    }

    @Test
    void adminOverrideRaisesMemberToolOnly() {
        assertEquals(ToolIdentity.LEVEL_ADMIN,
            ToolRegistry.effectiveMinRole(MEMBER_TOOL, Map.of("gate_test_tool", "admin")));
    }

    @Test
    void memberOverrideCannotLowerBuiltinAdminGate() {
        // 只升不降红线：内置 LEVEL_ADMIN 的工具配 member 不得降到全员
        assertEquals(ToolIdentity.LEVEL_ADMIN,
            ToolRegistry.effectiveMinRole(ADMIN_TOOL, Map.of("gate_admin_tool", "member")));
    }

    @Test
    void unknownValueOrUnrelatedKeyIgnored() {
        assertEquals(0, ToolRegistry.effectiveMinRole(MEMBER_TOOL, Map.of("gate_test_tool", "banana")));
        assertEquals(ToolIdentity.LEVEL_ADMIN,
            ToolRegistry.effectiveMinRole(ADMIN_TOOL, Map.of("other_tool", "member")));
    }
}
