package org.dromara.test.assistant;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.tool.Toolkit;
import org.dromara.assistant.core.AssistantWebSearchHolder;
import org.dromara.assistant.core.ToolRegistry;
import org.dromara.assistant.tools.ToolIdentity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S-3 工具注册表守卫单测（清剿批 2026-10-07；批3 微核心化重写 2026-10-18）：
 * 注册表是工具名/权限/门槛的唯一真相。批3 起本表只剩 6 个平台工具（业务工具全走
 * BizToolProvider SPI），本测试守三件事——①清单完整（6 名不丢不重 + TOOL_META 同步）；
 * ②配置门控（web_search 随会话联网开关、save_memory 随租户记忆开关）；
 * ③权限口径（平台工具全 ALLOW——ASK 业务工具在 SPI 侧注册，坑71 回归防线仍在）。
 *
 * <p>纯静态构造（不依赖 Spring 上下文与数据库）：工具构造器只存依赖引用，
 * null 依赖在本测试不触发调用。</p>
 */
@Tag("dev")
class ToolRegistryGuardTest {

    /** 业务批3-④：web_search 受会话联网开关门控（Holder 按 threadId 判定，未登记恒关）。
     *  守卫测试固定 threadId=thread-t，须显式置开，否则清单断言会少 web_search 一枚。 */
    @BeforeAll
    static void webOnForGuardThread() {
        AssistantWebSearchHolder.set("thread-t", "1");
    }

    /** 预期全量工具名（增删工具时同步改这里——本测试的存在就是逼人显式改清单）。
     *  批3 微核心化：18 个业务域工具退役（走 SPI），平台自留 6 个。 */
    private static final Set<String> EXPECTED_ALL = Set.of(
        "web_search", "read_attachment", "submit_plan", "save_memory", "todo_write", "view_skill");

    private static ToolIdentity identity(int level) {
        return new ToolIdentity(1L, "000000", level, null);
    }

    /** 平台工具无「缺依赖即缺席」gate——全 null 依赖即可构造（工具构造器只存引用）。 */
    private static ToolRegistry.ToolDeps readyDeps(Boolean memoryEnabled) {
        return readyDeps(memoryEnabled, "thread-t");
    }

    private static ToolRegistry.ToolDeps readyDeps(Boolean memoryEnabled, String threadId) {
        return new ToolRegistry.ToolDeps(
            null, null, null,
            null, null, memoryEnabled, threadId, null, null, null, null);
    }

    /** ①清单完整：全局名字无重复，与预期全集全等；每条 spec 字段齐。 */
    @Test
    void specListCompleteAndUnique() {
        List<String> seen = new ArrayList<>();
        for (ToolRegistry.ToolSpec spec : ToolRegistry.SPECS) {
            assertNotNull(spec.behavior(), "behavior 必填");
            assertNotNull(spec.factory(), "factory 必填");
            assertTrue(spec.minRoleLevel() >= ToolIdentity.LEVEL_MEMBER, "门槛合法");
            seen.addAll(spec.toolNames());
        }
        assertEquals(seen.size(), new LinkedHashSet<>(seen).size(), "工具名全局不重复");
        assertEquals(EXPECTED_ALL, ToolRegistry.allToolNames(), "全量工具名与预期清单一致");
        // dspro P2-5 回修：全景中文元数据是第二注册点，漏写 meta 管理页会静默显示英文码——
        // 守卫逼新增工具时 SPECS 与 TOOL_META 两处同步
        assertEquals(ToolRegistry.allToolNames(), ToolRegistry.TOOL_META.keySet(), "TOOL_META 与 SPECS 工具名一一对应");
    }

    /** ②角色口径：平台工具全 LEVEL_MEMBER 档——成员即全量（业务面的角色裁剪已随
     *  SPI 迁走，由 ProviderToolRegistry 按 provider 声明的 minRole/permission 执行）。 */
    @Test
    void memberSeesAllPlatformTools() {
        Set<String> studentNames = ToolRegistry.registerAll(new Toolkit(), identity(ToolIdentity.LEVEL_MEMBER),
            readyDeps(true), PermissionContextState.builder(), null);
        assertEquals(EXPECTED_ALL, studentNames, "成员=平台全量 6 工具（无角色裁剪）");
    }

    /** ②b 配置门控（二站④）：租户记忆关（null 或 false）→ save_memory 不注册。 */
    @Test
    void memoryGateCutsSaveMemoryWhenDisabled() {
        for (Boolean mem : new Boolean[] {null, false}) {
            Set<String> names = ToolRegistry.registerAll(new Toolkit(), identity(ToolIdentity.LEVEL_ADMIN),
                readyDeps(mem), PermissionContextState.builder(), null);
            assertFalse(names.contains("save_memory"), "记忆关(" + mem + ")时 save_memory 不可见");
            assertEquals(EXPECTED_ALL.size() - 1, names.size());
        }
    }

    /** ②d 会话联网门控（业务批3-④）：联网关的线程（thread-off 未在 Holder 置开）
     *  → web_search 不注册（gate 真实生效的回归线，配套 @BeforeAll 的 thread-t 置开对照）。 */
    @Test
    void webSearchGateCutsWhenThreadWebOff() {
        Set<String> names = ToolRegistry.registerAll(new Toolkit(), identity(ToolIdentity.LEVEL_ADMIN),
            readyDeps(true, "thread-off"), PermissionContextState.builder(), null);
        assertFalse(names.contains("web_search"), "联网关的线程不可见 web_search");
        assertEquals(EXPECTED_ALL.size() - 1, names.size(), "仅少 web_search 一枚，其余不受联网门控影响");
    }

    /** ②e 权限通配三态口径（批2 glm53 P2-8 单点化）：ProviderToolRegistry 的 provider
     *  权限门共用本方法——null 拒 / 裸 * 与 *:*:* 通配放行 / 精确串才比对。 */
    @Test
    void wildcardOrContainsSemantics() {
        assertTrue(ToolRegistry.wildcardOrContains(java.util.Set.of("*:*:*"), "dcs:task:add"), "*:*:* 超管真实形态放行");
        assertTrue(ToolRegistry.wildcardOrContains(java.util.Set.of("*"), "dcs:task:add"), "裸 * 放行");
        assertTrue(ToolRegistry.wildcardOrContains(java.util.Set.of("dcs:task:add"), "dcs:task:add"), "精确串放行");
        assertFalse(ToolRegistry.wildcardOrContains(java.util.Set.of("dcs:task:list"), "dcs:task:add"), "无 add 且非通配拒");
        assertFalse(ToolRegistry.wildcardOrContains(null, "dcs:task:add"), "null 拒");
        assertFalse(ToolRegistry.wildcardOrContains(java.util.Set.of(), "dcs:task:add"), "空集拒");
    }

    /** ③权限口径守卫：平台工具全 ALLOW（坑71 回归防线；ASK 型确认卡工具只在 SPI 侧注册）。 */
    @Test
    void permissionMatrixMatchesSpec() {
        for (ToolRegistry.ToolSpec spec : ToolRegistry.SPECS) {
            assertEquals(PermissionBehavior.ALLOW,
                spec.behavior(), "平台工具 " + spec.toolNames() + " 全 ALLOW（动作类须走 BizToolProvider SPI）");
        }
    }
}
