package com.smartdock.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.assistant.controller.AssistantConversationController;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话线 6 个端点的契约钉（批 7 · 7B，2026-11-20）。
 *
 * <p>为什么需要它：7B 把 6 个端点的实现从 legacy 胖服务换到新根 query / meta 两个服务，
 * 且 {@code byThread} 的归属判定从控制器下沉到服务——编译器不认「路径 / HTTP 动词 /
 * 权限串 / 审计注解」，谁顺手改掉（POST→GET、use 降级、@Log 丢失）也能全绿。
 * 本类把 6 条逐字固定，同时钉住整个类的 handler 总数（17），新增/删除端点必须同步改这里。</p>
 */
@Tag("local")
class AssistantConversationEndpointContractTest {

    private static final String BASE = "/assistant";
    private static final String USE = "smartdock:assistant:use";

    private static void assertClassMapping(Class<?> c, String expected) {
        RequestMapping rm = c.getAnnotation(RequestMapping.class);
        assertThat(rm).as(c.getSimpleName() + " 缺 @RequestMapping").isNotNull();
        assertThat(Arrays.asList(rm.value())).as(c.getSimpleName() + " 类路径").containsExactly(expected);
    }

    private static void assertEndpoint(Class<?> c, String methodName, Class<? extends Annotation> mapping,
                                       String path, String permission) throws Exception {
        Method m = methodByName(c, methodName);
        assertThat(Modifier.isPublic(m.getModifiers())).as(methodName + " 须是 public handler").isTrue();

        Annotation anno = m.getAnnotation(mapping);
        assertThat(anno).as(methodName + " 缺 " + mapping.getSimpleName()).isNotNull();
        String[] value = (String[]) mapping.getMethod("value").invoke(anno);
        assertThat(Arrays.asList(value)).as(methodName + " 路径").containsExactly(path);

        SaCheckPermission perm = m.getAnnotation(SaCheckPermission.class);
        assertThat(perm).as(methodName + " 缺 @SaCheckPermission").isNotNull();
        assertThat(perm.value()).as(methodName + " 权限串").containsExactly(permission);
    }

    private static void assertLog(Class<?> c, String methodName, String title, BusinessType type)
        throws Exception {
        Log log = methodByName(c, methodName).getAnnotation(Log.class);
        assertThat(log).as(c.getSimpleName() + "." + methodName + " 缺 @Log").isNotNull();
        assertThat(log.title()).as(methodName + " 审计标题").isEqualTo(title);
        assertThat(log.businessType()).as(methodName + " 业务类型").isEqualTo(type);
    }

    private static Method methodByName(Class<?> c, String name) {
        return Arrays.stream(c.getDeclaredMethods())
            .filter(x -> x.getName().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError(c.getSimpleName() + " 缺方法 " + name));
    }

    /** 7B 的 6 个端点：路径 / 动词 / 权限串逐字不变（前端零改动的前提） */
    @Test
    void conversationLineEndpointsKeepPathVerbAndPermission() throws Exception {
        Class<?> c = AssistantConversationController.class;
        assertClassMapping(c, BASE);

        assertEndpoint(c, "conversations", GetMapping.class, "/conversation/list", USE);
        assertEndpoint(c, "byThread", GetMapping.class, "/conversation/by-thread", USE);
        assertEndpoint(c, "messages", GetMapping.class, "/conversation/{id}/messages", USE);
        // 铁律#21：DELETE→POST 保持路径（前端已同步，批17 Q4）
        assertEndpoint(c, "archive", PostMapping.class, "/conversation/{id}", USE);
        assertEndpoint(c, "rename", PostMapping.class, "/conversation/{id}/title", USE);
        assertEndpoint(c, "restore", PostMapping.class, "/conversation/{id}/restore", USE);
    }

    /** 三个写端点必须留审计，标题/业务类型逐字固定（7B 未改，钉住防漂） */
    @Test
    void writeEndpointsKeepAuditLog() throws Exception {
        assertLog(AssistantConversationController.class, "archive", "AI助手-归档会话", BusinessType.DELETE);
        assertLog(AssistantConversationController.class, "rename", "AI助手-重命名会话", BusinessType.UPDATE);
        assertLog(AssistantConversationController.class, "restore", "AI助手-恢复会话", BusinessType.UPDATE);
    }

    /** handler 总数固定 17：7B 只改实现，不增删端点；新增会话端点请同步本测试 */
    @Test
    void noHandlerAddedOrRemovedSilently() {
        assertThat(countHandlers(AssistantConversationController.class))
            .as("AssistantConversationController handler 总数（新增/删除端点请同步本测试）")
            .isEqualTo(17);
    }

    private static int countHandlers(Class<?> c) {
        List<String> handlers = new ArrayList<>();
        for (Method m : c.getDeclaredMethods()) {
            if (m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class)
                || m.isAnnotationPresent(PutMapping.class) || m.isAnnotationPresent(DeleteMapping.class)
                || m.isAnnotationPresent(PatchMapping.class)) {
                handlers.add(m.getName());
            }
        }
        return handlers.size();
    }
}
