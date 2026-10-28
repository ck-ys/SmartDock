package org.dromara.assistant.controller;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 5b 清尾·件1（2026-10-28 拍板题2-修）：矩阵整存「吞停用技能档位行」的纯函数护栏。
 * 口径：停用技能在矩阵视图不显示（遮罩），整存时其覆盖档必须保留原值；
 * 正常技能/工具行的缺键仍等于恢复跟随内置档，语义不变。
 */
class AssistantToolGateControllerMergeUnitTest {

    @Test
    @Tag("local")
    void 停用技能键缺失时保留原值() {
        Map<String, String> current = new HashMap<>(Map.of(
            "skill:weekly-report", "admin",
            "skill:data-reading", "off",
            "queryProfile", "admin"));
        Map<String, String> merged = AssistantToolGateController.preserveHiddenSkillOverrides(
            Map.of("queryProfile", "admin"),
            current, Set.of("weekly-report", "data-reading"));
        assertThat(merged.get("skill:weekly-report")).isEqualTo("admin");
        assertThat(merged.get("skill:data-reading")).isEqualTo("off");
        assertThat(merged.get("queryProfile")).isEqualTo("admin");
    }

    @Test
    @Tag("local")
    void 停用技能键显式带了新值时用新值() {
        Map<String, String> merged = AssistantToolGateController.preserveHiddenSkillOverrides(
            Map.of("skill:weekly-report", "member"),
            Map.of("skill:weekly-report", "admin"),
            Set.of("weekly-report"));
        assertThat(merged.get("skill:weekly-report")).isEqualTo("member");
    }

    @Test
    @Tag("local")
    void 正常技能键缺失仍等于恢复默认() {
        Map<String, String> merged = AssistantToolGateController.preserveHiddenSkillOverrides(
            Map.of(),
            Map.of("skill:normal-one", "admin", "queryProfile", "off"),
            Set.of("weekly-report"));
        assertThat(merged).doesNotContainKey("skill:normal-one");
        assertThat(merged).doesNotContainKey("queryProfile");
    }

    @Test
    @Tag("local")
    void 停用名单为空时原样透传() {
        Map<String, String> incoming = Map.of("skill:any", "off");
        Map<String, String> merged = AssistantToolGateController.preserveHiddenSkillOverrides(
            incoming, Map.of("skill:any", "admin"), Set.of());
        assertThat(merged).containsExactlyInAnyOrderEntriesOf(incoming);
    }

    @Test
    @Tag("local")
    void null键与空现网不炸() {
        Map<String, String> current = new HashMap<>();
        current.put(null, "off");
        Map<String, String> merged = AssistantToolGateController.preserveHiddenSkillOverrides(
            Map.of("queryProfile", "admin"), current, Set.of("x"));
        assertThat(merged).containsEntry("queryProfile", "admin");
        assertThat(merged).doesNotContainKey(null);
    }
}
