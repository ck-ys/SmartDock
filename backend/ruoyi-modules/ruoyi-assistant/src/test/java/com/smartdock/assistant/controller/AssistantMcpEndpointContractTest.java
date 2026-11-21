package com.smartdock.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.log.annotation.Log;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP / 工具闸 8 个端点的契约钉（2026-11-19 批 5 多审第二轮 m3 P2-03 回修）。
 *
 * <p>为什么需要它：批 5 是「整类换位置」的动作，而全仓此前没有任何测试钉住这两个 controller
 * 的路径、HTTP 方法、权限串——谁把这些顺手改掉（例如把 save 的 config 降成 use、把 POST 改 GET），
 * 编译、单测、架构门禁全绿。本测试把 8 条契约逐字固定，新增/删除端点而不更新这里也会红。</p>
 *
 * <p>另钉一条安全细节：{@code save} 的 {@code @Log(isSaveRequestData=false)}——
 * 请求体里带 headers（可能是令牌），落操作日志即泄漏。</p>
 */
@Tag("local")
class AssistantMcpEndpointContractTest {

    private static final String MCP = "/assistant/mcp";
    private static final String GATE = "/assistant/toolgate";

    private static void assertClassMapping(Class<?> c, String expected) {
        RequestMapping rm = c.getAnnotation(RequestMapping.class);
        assertThat(rm).as(c.getSimpleName() + " 缺 @RequestMapping").isNotNull();
        assertThat(Arrays.asList(rm.value())).as(c.getSimpleName() + " 类路径").containsExactly(expected);
    }

    private static void assertEndpoint(Class<?> c, String methodName, Class<? extends Annotation> mapping,
                                       String path, String permission) throws Exception {
        Method m = Arrays.stream(c.getDeclaredMethods())
            .filter(x -> x.getName().equals(methodName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(c.getSimpleName() + " 缺方法 " + methodName));

        assertThat(Modifier.isPublic(m.getModifiers())).as(methodName + " 须是 public handler").isTrue();

        Annotation anno = m.getAnnotation(mapping);
        assertThat(anno).as(methodName + " 缺 " + mapping.getSimpleName()).isNotNull();
        String[] value = (String[]) mapping.getMethod("value").invoke(anno);
        if (path.isEmpty()) {
            // 裸 @GetMapping / @PostMapping：value() 是空数组，路径全部继承类级映射
            assertThat(value).as(methodName + " 路径应继承类级映射").isEmpty();
        } else {
            assertThat(Arrays.asList(value)).as(methodName + " 路径").containsExactly(path);
        }

        SaCheckPermission perm = m.getAnnotation(SaCheckPermission.class);
        assertThat(perm).as(methodName + " 缺 @SaCheckPermission").isNotNull();
        assertThat(perm.value()).as(methodName + " 权限串").containsExactly(permission);
    }

    @Test
    void mcpEndpointsKeepPathVerbAndPermission() throws Exception {
        Class<?> c = AssistantMcpController.class;
        assertClassMapping(c, MCP);

        assertEndpoint(c, "memberList", GetMapping.class, "/member-list", "smartdock:assistant:use");
        assertEndpoint(c, "list", GetMapping.class, "/list", "smartdock:assistant:config");
        assertEndpoint(c, "save", PostMapping.class, "/save", "smartdock:assistant:config");
        assertEndpoint(c, "toggle", PostMapping.class, "/toggle", "smartdock:assistant:config");
        assertEndpoint(c, "delete", PostMapping.class, "/delete", "smartdock:assistant:config");
        assertEndpoint(c, "callLog", GetMapping.class, "/calllog", "smartdock:assistant:config");
    }

    @Test
    void toolGateEndpointsKeepPathVerbAndPermission() throws Exception {
        Class<?> c = AssistantToolGateController.class;
        assertClassMapping(c, GATE);

        assertEndpoint(c, "view", GetMapping.class, "", "smartdock:assistant:config");
        assertEndpoint(c, "save", PostMapping.class, "", "smartdock:assistant:config");
    }

    /** 端点总数固定 8：新增/删除 handler 必须同步改上面的契约表 */
    @Test
    void noHandlerAddedOrRemovedSilently() {
        int total = 0;
        for (Class<?> c : List.of(AssistantMcpController.class, AssistantToolGateController.class)) {
            total += countHandlers(c);
        }
        assertThat(total).as("MCP + 工具闸 handler 总数（新增端点请同步本测试）").isEqualTo(8);
    }

    /** 保存端点不得把请求体写进操作日志（headers 里可能是令牌） */
    @Test
    void mcpSaveDoesNotPersistRequestBodyInOperationLog() throws Exception {
        Method save = Arrays.stream(AssistantMcpController.class.getDeclaredMethods())
            .filter(x -> x.getName().equals("save")).findFirst().orElseThrow();
        Log log = save.getAnnotation(Log.class);
        assertThat(log).as("save 缺 @Log（写操作须留审计）").isNotNull();
        assertThat(log.isSaveRequestData()).as("save 的请求体含 headers，禁止落操作日志").isFalse();
    }

    private static int countHandlers(Class<?> c) {
        List<String> handlers = new ArrayList<>();
        for (Method m : c.getDeclaredMethods()) {
            if (m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class)
                || m.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)
                || m.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class)
                || m.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping.class)) {
                handlers.add(m.getName());
            }
        }
        return handlers.size();
    }
}
