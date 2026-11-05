package org.dromara.assistant.controller;

import org.dromara.assistant.core.ToolRegistry;
import org.dromara.assistant.tools.ToolIdentity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作台设置页工具清单（2026-11-05 题1-3 全甲）：状态推导与「用户向一句话」的纯函数护栏。
 * 口径：状态必须能区分「可用 / 等租户开启 / 已停用 / 管理员限定」四种；
 * 一句人话来自 TOOL_META 单点，不许出现空白（新增工具漏写会被这里拦下）。
 */
class AssistantToolViewerUnitTest {

    @Test
    @Tag("local")
    void 租户关掉是已停用() {
        var st = AssistantToolViewerController.deriveStatus(-1, true);
        assertThat(st.code()).isEqualTo("off");
        assertThat(st.text()).isEqualTo("已停用");
    }

    @Test
    @Tag("local")
    void 提到管理员档是管理员限定() {
        var st = AssistantToolViewerController.deriveStatus(ToolIdentity.LEVEL_ADMIN, true);
        assertThat(st.code()).isEqualTo("admin");
        assertThat(st.text()).isEqualTo("管理员限定");
    }

    @Test
    @Tag("local")
    void 注册闸未开是等租户开启() {
        var st = AssistantToolViewerController.deriveStatus(ToolIdentity.LEVEL_MEMBER, false);
        assertThat(st.code()).isEqualTo("waiting");
        assertThat(st.text()).isEqualTo("等租户开启");
    }

    @Test
    @Tag("local")
    void 管理员档但闸没开仍说等租户开启() {
        // 闸没开时对管理员也不可见，写「管理员限定」会误导
        var st = AssistantToolViewerController.deriveStatus(ToolIdentity.LEVEL_ADMIN, false);
        assertThat(st.code()).isEqualTo("waiting");
        assertThat(st.text()).isEqualTo("等租户开启");
    }

    @Test
    @Tag("local")
    void 成员档且闸开是可用() {
        var st = AssistantToolViewerController.deriveStatus(ToolIdentity.LEVEL_MEMBER, true);
        assertThat(st.code()).isEqualTo("ready");
        assertThat(st.text()).isEqualTo("可用");
    }

    @Test
    @Tag("local")
    void 谁能用按生效档给四种答案() {
        assertThat(AssistantToolViewerController.whoCanUse(-1)).isEqualTo("—");
        assertThat(AssistantToolViewerController.whoCanUse(ToolIdentity.LEVEL_MEMBER)).isEqualTo("成员");
        assertThat(AssistantToolViewerController.whoCanUse(ToolIdentity.LEVEL_PUBLISHER)).isEqualTo("发布人及以上");
        assertThat(AssistantToolViewerController.whoCanUse(ToolIdentity.LEVEL_ADMIN)).isEqualTo("管理员");
    }

    @Test
    @Tag("local")
    void 每个平台工具都有非空的一句话且长度可控() {
        assertThat(ToolRegistry.TOOL_META).hasSize(ToolRegistry.SPECS.size());
        for (var e : ToolRegistry.TOOL_META.entrySet()) {
            assertThat(e.getValue().userDesc())
                .as("工具 %s 缺用户向一句话", e.getKey())
                .isNotBlank()
                .hasSizeLessThanOrEqualTo(40);
        }
    }

    @Test
    @Tag("local")
    void 平台工具与一句话按首个工具名严格一一对应() {
        // hasSize 相等不等于键对得上：新增工具改了首名、或 TOOL_META 写错 key，都要在这里炸
        var firstNameOfSpecs = new java.util.HashSet<String>();
        for (var spec : ToolRegistry.SPECS) {
            firstNameOfSpecs.add(spec.toolNames().stream().sorted().findFirst().orElse(""));
        }
        assertThat(ToolRegistry.TOOL_META.keySet())
            .containsExactlyInAnyOrderElementsOf(firstNameOfSpecs);
    }
}
