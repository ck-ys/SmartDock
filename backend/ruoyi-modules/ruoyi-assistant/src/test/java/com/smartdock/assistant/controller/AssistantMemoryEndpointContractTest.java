package com.smartdock.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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
 * 记忆 / 收藏 / 反馈 15 个端点的契约钉（批 6 题 1 案 1，2026-11-20）。
 *
 * <p>为什么需要它：批 6 把三个 controller 整类换了包（legacy.controller → controller），
 * 前后端零改动的前提是路径、HTTP 方法、权限串逐字不变。编译器不认这三样，谁顺手改掉
 * （把 POST 改 GET、把 memory:manage 降成 use）编译/单测/架构门禁全绿；本测试把 15 条
 * 契约逐字固定，新增/删除端点而不更新这里也会红。</p>
 *
 * <p>写操作另钉一条：必须带 {@code @Log}（编辑/开关/清空/删除/反馈提交）。</p>
 */
@Tag("local")
class AssistantMemoryEndpointContractTest {

    private static final String MEMORY = "/dcs/assistant/memory";
    private static final String FAVORITE = "/dcs/assistant/favorite";
    private static final String FEEDBACK = "/dcs/assistant";
    private static final String MEMORY_PERM = "dcs:assistant:memory:manage";
    private static final String USE_PERM = "dcs:assistant:use";
    private static final String FEEDBACK_QUERY = "dcs:assistant:feedback:query";

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
        assertThat(Arrays.asList(value)).as(methodName + " 路径").containsExactly(path);

        SaCheckPermission perm = m.getAnnotation(SaCheckPermission.class);
        assertThat(perm).as(methodName + " 缺 @SaCheckPermission").isNotNull();
        assertThat(perm.value()).as(methodName + " 权限串").containsExactly(permission);
    }

    @Test
    void memoryEndpointsKeepPathVerbAndPermission() throws Exception {
        Class<?> c = AssistantMemoryController.class;
        assertClassMapping(c, MEMORY);

        assertEndpoint(c, "list", GetMapping.class, "/list", MEMORY_PERM);
        assertEndpoint(c, "history", GetMapping.class, "/history", MEMORY_PERM);
        assertEndpoint(c, "usage", GetMapping.class, "/usage", MEMORY_PERM);
        assertEndpoint(c, "edit", PostMapping.class, "/{id}/edit", MEMORY_PERM);
        assertEndpoint(c, "pref", GetMapping.class, "/pref", MEMORY_PERM);
        assertEndpoint(c, "setPref", PostMapping.class, "/pref", MEMORY_PERM);
        assertEndpoint(c, "export", GetMapping.class, "/export", MEMORY_PERM);
        assertEndpoint(c, "clear", PostMapping.class, "/clear", MEMORY_PERM);
        // 铁律#21：DELETE→POST 保持路径（前端已同步，批17 Q4）
        assertEndpoint(c, "remove", PostMapping.class, "/{id}", MEMORY_PERM);
    }

    @Test
    void favoriteEndpointsKeepPathVerbAndPermission() throws Exception {
        Class<?> c = AssistantFavoriteController.class;
        assertClassMapping(c, FAVORITE);

        assertEndpoint(c, "list", GetMapping.class, "/list", USE_PERM);
        assertEndpoint(c, "add", PostMapping.class, "/add", USE_PERM);
        assertEndpoint(c, "remove", PostMapping.class, "/remove", USE_PERM);
    }

    @Test
    void feedbackEndpointsKeepPathVerbAndPermission() throws Exception {
        Class<?> c = AssistantFeedbackController.class;
        assertClassMapping(c, FEEDBACK);

        assertEndpoint(c, "submit", PostMapping.class, "/feedback", USE_PERM);
        assertEndpoint(c, "stats", GetMapping.class, "/feedback/stats", FEEDBACK_QUERY);
        assertEndpoint(c, "list", GetMapping.class, "/feedback/list", FEEDBACK_QUERY);
    }

    /** 端点总数固定 15：新增/删除 handler 必须同步改上面的契约表 */
    @Test
    void noHandlerAddedOrRemovedSilently() {
        int total = 0;
        for (Class<?> c : List.of(AssistantMemoryController.class, AssistantFavoriteController.class,
            AssistantFeedbackController.class)) {
            total += countHandlers(c);
        }
        assertThat(total).as("记忆 + 收藏 + 反馈 handler 总数（新增端点请同步本测试）").isEqualTo(15);
    }

    /** 写操作必须留审计（编辑/开关/清空/删除/反馈提交），且标题/业务类型逐字固定 */
    @Test
    void writeHandlersKeepAuditLog() throws Exception {
        assertLog(AssistantMemoryController.class, "edit", "AI助手-编辑记忆", BusinessType.UPDATE);
        assertLog(AssistantMemoryController.class, "setPref", "AI助手-记忆开关", BusinessType.UPDATE);
        assertLog(AssistantMemoryController.class, "clear", "AI助手-清空记忆", BusinessType.DELETE);
        assertLog(AssistantMemoryController.class, "remove", "AI助手-删除记忆", BusinessType.DELETE);
        assertLog(AssistantFeedbackController.class, "submit", "AI助手-反馈", BusinessType.INSERT);
    }

    /** 正文可能含 PII 的两个写端点：审计日志不得留存请求体（2026-11-20 多审 m3 P1） */
    @Test
    void piiWriteHandlersDoNotSaveRequestBody() throws Exception {
        Method edit = Arrays.stream(AssistantMemoryController.class.getDeclaredMethods())
            .filter(x -> x.getName().equals("edit")).findFirst().orElseThrow();
        assertThat(edit.getAnnotation(Log.class).isSaveRequestData())
            .as("AssistantMemoryController.edit 不得把请求正文写进操作日志").isFalse();
        Method submit = Arrays.stream(AssistantFeedbackController.class.getDeclaredMethods())
            .filter(x -> x.getName().equals("submit")).findFirst().orElseThrow();
        assertThat(submit.getAnnotation(Log.class).isSaveRequestData())
            .as("AssistantFeedbackController.submit 不得把请求正文写进操作日志").isFalse();
    }

    private static void assertLog(Class<?> c, String methodName, String title, BusinessType type)
        throws Exception {
        Method m = Arrays.stream(c.getDeclaredMethods())
            .filter(x -> x.getName().equals(methodName)).findFirst().orElseThrow();
        Log log = m.getAnnotation(Log.class);
        assertThat(log).as(c.getSimpleName() + "." + methodName + " 缺 @Log").isNotNull();
        assertThat(log.title()).as(methodName + " 审计标题").isEqualTo(title);
        assertThat(log.businessType()).as(methodName + " 业务类型").isEqualTo(type);
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
