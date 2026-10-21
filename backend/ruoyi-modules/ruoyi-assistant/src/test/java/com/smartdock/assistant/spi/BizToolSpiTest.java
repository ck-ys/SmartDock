package com.smartdock.assistant.spi;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.dromara.assistant.core.BizToolAgentAdapter;
import org.dromara.assistant.core.JsonSchemaBuilder;
import org.dromara.assistant.core.ProviderToolRegistry;
import org.dromara.assistant.tools.ToolIdentity;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Toolkit;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPI 桥接单测(V1-V5): 玩具 provider 注册即被发现 + 门控 + 撞名让位 + 工厂解耦。
 *
 * <p>不起 Spring: registry 用 ObjectProvider 假实现喂; serializer 换原生 Jackson。
 * agentscope Toolkit/PermissionContextState 均为 POJO, 纯 JUnit 可直构。</p>
 *
 * @author smartdock
 */
@Tag("local")
class BizToolSpiTest {

    /** 玩具 provider: 一个只读 + 一个写(ASK)工具, execute 回显入参 */
    static class ToyProvider implements BizToolProvider {
        public String namespace() { return "toy"; }

        public List<BizToolSpec> tools() {
            BizToolSpec echo = BizToolSpec.readOnly("toy_echo", "回声", "原样返回输入文本(测试用)");
            echo.getParams().add(BizParamSpec.string("text", "要回声的文本", true));

            BizToolSpec poke = BizToolSpec.write("toy_poke", "戳一下", "模拟写操作(测试用)", "确认要戳一下吗");
            poke.getParams().add(BizParamSpec.number("times", "戳几下", false));
            return List.of(echo, poke);
        }

        public BizToolResult execute(BizToolCall call) {
            if ("toy_poke".equals(call.getToolName())) {
                return BizToolResult.ok(Map.of("poked", call.getArgs().get("times")));
            }
            return BizToolResult.ok(Map.of("echo", call.getArgs().get("text"),
                "user", call.getUserId(), "tenant", call.getTenantId()));
        }
    }

    /** ObjectProvider 假实现: 手工喂 provider 列表 */
    static class FakeObjectProvider implements org.springframework.beans.factory.ObjectProvider<BizToolProvider> {
        private final List<BizToolProvider> list;
        FakeObjectProvider(List<BizToolProvider> list) { this.list = list; }
        public BizToolProvider getObject(Object... args) { return list.get(0); }
        public BizToolProvider getObject() { return list.get(0); }
        public BizToolProvider getIfAvailable() { return list.isEmpty() ? null : list.get(0); }
        public BizToolProvider getIfUnique() { return list.isEmpty() ? null : list.get(0); }
        @SuppressWarnings("unchecked")
        public java.util.Iterator<BizToolProvider> iterator() { return list.iterator(); }
        public java.util.stream.Stream<BizToolProvider> stream() { return list.stream(); }
    }

    private static ToolIdentity identity(int level, Set<String> perms) {
        org.dromara.common.core.domain.model.LoginUser user = new org.dromara.common.core.domain.model.LoginUser();
        user.setMenuPermission(perms);
        return new ToolIdentity(1L, "000000", level, user);
    }

    @org.junit.jupiter.api.BeforeAll
    static void plainSerializer() {
        // JsonUtils 静态依赖 Spring 上下文, 单测换原生 Jackson
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        BizToolAgentAdapter.serializer = o -> {
            try { return m.writeValueAsString(o); } catch (Exception e) { throw new RuntimeException(e); }
        };
    }

    @org.junit.jupiter.api.AfterAll
    static void restoreSerializer() {
        // dspro P2-9: serializer 是全局静态钩子, 用完还原防污染同 JVM 其它用例
        BizToolAgentAdapter.serializer = org.dromara.common.json.utils.JsonUtils::toJsonString;
    }

    @Test
    void v2_toyProviderDiscoveredAndCallable() {
        ProviderToolRegistry registry = new ProviderToolRegistry(new FakeObjectProvider(List.of(new ToyProvider())));
        Toolkit toolkit = new Toolkit();
        PermissionContextState.Builder pb = PermissionContextState.builder();
        Set<String> registered = registry.registerProviders(toolkit, identity(0, Set.of()), "t1", pb);

        assertThat(registered).containsExactlyInAnyOrder("toy_echo", "toy_poke");
        assertThat(toolkit.getToolNames()).contains("toy_echo", "toy_poke");

        AgentTool echo = toolkit.getTool("toy_echo");
        assertThat(echo).isNotNull();
        assertThat(echo.isReadOnly()).isTrue();
        assertThat(toolkit.getTool("toy_poke").isReadOnly()).isFalse();

        // schema 由参数声明生成
        Map<String, Object> schema = echo.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("text");
        assertThat(schema.get("required")).isEqualTo(List.of("text"));

        // 执行链: 入参过滤 + 身份透传
        io.agentscope.core.message.ToolResultBlock out =
            ((BizToolAgentAdapter) echo).doExecute(Map.of("text", "hi", "junk", "幻觉多余键"));
        assertThat(out.getOutput()).hasSize(1);
        String json = out.getOutput().get(0) instanceof io.agentscope.core.message.TextBlock tb
            ? tb.getText() : out.getOutput().get(0).toString();
        assertThat(json).contains("\"echo\":\"hi\"").contains("\"user\":1").contains("\"tenant\":\"000000\"");
        assertThat(json).doesNotContain("junk"); // 未声明参数被剔除
    }

    @Test
    void v3_roleAndPermissionGates() {
        ProviderToolRegistry registry = new ProviderToolRegistry(new FakeObjectProvider(List.of(new GatedProvider())));
        Toolkit toolkit = new Toolkit();
        Set<String> registered = registry.registerProviders(toolkit, identity(0, Set.of()), null,
            PermissionContextState.builder());
        // 成员档: role>=1 的工具跳过; 需权限串的跳过
        assertThat(registered).containsExactly("toy_public");

        // 管理员档 + 通配权限: 全过
        Toolkit toolkit2 = new Toolkit();
        Set<String> registered2 = registry.registerProviders(toolkit2, identity(2, Set.of("*:*:*")), null,
            PermissionContextState.builder());
        assertThat(registered2).containsExactlyInAnyOrder("toy_public", "toy_senior", "toy_perm");
    }

    @Test
    void v4_nameConflictYieldsToBuiltin() {
        // provider 用平台内置工具名 view_skill → 让位不注册, 其余工具照常
        // (批3 微核心化: 内置面=平台 6 工具, 撞名样例随注册表更新)
        ProviderToolRegistry registry = new ProviderToolRegistry(new FakeObjectProvider(List.of(new ClashProvider())));
        Toolkit toolkit = new Toolkit();
        Set<String> registered = registry.registerProviders(toolkit, identity(0, Set.of()), null,
            PermissionContextState.builder());
        assertThat(registered).containsExactly("toy_fine");
        assertThat(toolkit.getToolNames()).doesNotContain("view_skill");
    }

    @Test
    void v5_platformToolsDontNeedBusinessModules() {
        // 批3 微核心化: ToolDeps 只剩平台依赖, 业务工具全走 SPI——全 null 依赖即可装配
        org.dromara.assistant.core.ToolRegistry.ToolDeps emptyDeps =
            new org.dromara.assistant.core.ToolRegistry.ToolDeps(
                null, null, null, null, null, Boolean.FALSE, "t", null, null, null, null);
        Toolkit toolkit = new Toolkit();
        Set<String> registered = org.dromara.assistant.core.ToolRegistry.registerAll(toolkit,
            identity(2, Set.of("*:*:*")), emptyDeps, PermissionContextState.builder(), null);

        assertThat(registered).doesNotContain("query_my_todos", "query_my_missions", "act_remind",
            "act_forward", "act_audit", "act_create_mission", "act_create_template",
            "query_pending_reviews", "search_user", "query_dashboard");
        // 平台工具仍注册(记忆关=save_memory 不在; 联网开关关=web_search 不在)
        assertThat(registered).contains("view_skill", "submit_plan", "todo_write", "read_attachment");
    }

    @Test
    void v6_emptyProviderConstructsAndRegistersNothing() {
        // LongCat P2-2: 零 provider Bean 的空表路径——注册器构造成立、注册返回空集
        ProviderToolRegistry registry = new ProviderToolRegistry(new FakeObjectProvider(List.of()));
        Set<String> registered = registry.registerProviders(new Toolkit(), identity(0, Set.of()), null,
            PermissionContextState.builder());
        assertThat(registered).isEmpty();
        assertThat(registry.providers()).isEmpty();
    }

    @Test
    void v1_schemaBuilderMapsTypes() {
        BizToolSpec spec = BizToolSpec.readOnly("x", "x", "x");
        spec.getParams().add(BizParamSpec.enumeration("level", "档位", true, List.of("low", "high")));
        spec.getParams().add(BizParamSpec.number("count", "数量", false));
        spec.getParams().add(BizParamSpec.bool("dry", "演练", false));
        Map<String, Object> schema = JsonSchemaBuilder.build(spec);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat((Map<String, Object>) props.get("level"))
            .containsEntry("type", "string").containsEntry("enum", List.of("low", "high"));
        assertThat((Map<String, Object>) props.get("count")).containsEntry("type", "number");
        assertThat((Map<String, Object>) props.get("dry")).containsEntry("type", "boolean");
        assertThat(schema.get("required")).isEqualTo(List.of("level"));
    }

    /** 门控测试 provider: 公开 / 高档 / 权限串 三件 */
    static class GatedProvider implements BizToolProvider {
        public String namespace() { return "toy"; }
        public List<BizToolSpec> tools() {
            BizToolSpec pub = BizToolSpec.readOnly("toy_public", "公开", "谁都能用");
            BizToolSpec senior = BizToolSpec.readOnly("toy_senior", "高档", "发布人起");
            senior.setMinRoleLevel(1);
            BizToolSpec perm = BizToolSpec.readOnly("toy_perm", "权限门", "要权限串");
            perm.setRequiredPermission("toy:special:use");
            return List.of(pub, senior, perm);
        }
        public BizToolResult execute(BizToolCall call) { return BizToolResult.ok(Map.of("name", call.getToolName())); }
    }

    /** 撞名测试 provider */
    static class ClashProvider implements BizToolProvider {
        public String namespace() { return "toy"; }
        public List<BizToolSpec> tools() {
            BizToolSpec clash = BizToolSpec.readOnly("view_skill", "冒名", "撞内置名");
            BizToolSpec fine = BizToolSpec.readOnly("toy_fine", "正常", "没撞");
            return List.of(clash, fine);
        }
        public BizToolResult execute(BizToolCall call) { return BizToolResult.ok(Map.of()); }
    }
}
