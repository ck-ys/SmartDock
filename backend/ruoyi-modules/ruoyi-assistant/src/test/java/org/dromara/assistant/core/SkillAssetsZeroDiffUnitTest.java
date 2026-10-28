package org.dromara.assistant.core;

import org.dromara.assistant.tools.ToolIdentity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 技能门面口径护栏（R1 起家；B 批装载替换 2026-10-27 改口径）：SkillAssets 兼容门面读
 * {@link AssistantSkillSource}（官方仓）后，对外行为与内嵌对照表逐项一致——body/exists/
 * Holder 语义钉死。资产格式本身的零漂移护栏在 SkillOfficialFormatZeroDiffUnitTest
 * （官方全管线 vs 内嵌表）；本测试管「门面读新源后语义不变」。
 */
class SkillAssetsZeroDiffUnitTest {

    @Test
    @Tag("dev")
    void facadeSkillsMatchEmbeddedTable() {
        // 运行时门面（官方仓合并视图）与内嵌对照表逐字段一致——装载替换零行为变化的机器验收
        List<SkillAssets.LoadedSkill> loaded = SkillAssets.skills();
        assertThat(loaded).extracting(SkillAssets.LoadedSkill::id)
            .containsExactlyElementsOf(SkillAssets.SKILLS.stream().map(SkillAssets.Skill::id).toList());
        for (SkillAssets.LoadedSkill ls : loaded) {
            SkillAssets.Skill src = SkillAssets.SKILLS.stream()
                .filter(s -> s.id().equals(ls.id())).findFirst().orElseThrow();
            assertThat(ls.name()).as("技能 %s 名称", ls.id()).isEqualTo(src.name());
            assertThat(ls.description()).as("技能 %s 描述", ls.id()).isEqualTo(src.description());
            assertThat(ls.minRole()).as("技能 %s 门槛", ls.id()).isEqualTo(src.minRole());
            assertThat(ls.body()).as("技能 %s 正文", ls.id()).isEqualTo(src.embeddedBody().strip());
        }
    }

    @Test
    @Tag("dev")
    void bodyRoleGateAndUnknownId() {
        assertThat(SkillAssets.body("data-reading", ToolIdentity.LEVEL_MEMBER)).startsWith("你正在使用「数据解读」");
        // 成员档取发布人技能=null（目录侧同口径裁剪，正常不会出现——装饰器/工具兜底）
        assertThat(SkillAssets.body("create-mission", ToolIdentity.LEVEL_MEMBER)).isNull();
        assertThat(SkillAssets.body("no-such-skill", ToolIdentity.LEVEL_ADMIN)).isNull();
        assertThat(SkillAssets.body(null, ToolIdentity.LEVEL_ADMIN)).isNull();
        assertThat(SkillAssets.body(" data-reading ", ToolIdentity.LEVEL_MEMBER)).isNotNull();
        assertThat(SkillAssets.exists("urge-wording")).isTrue();
        assertThat(SkillAssets.exists("bogus")).isFalse();
    }

    @Test
    @Tag("dev")
    void holderClearsOnUnknownOrNullId() {
        // 5b 起 set 带租户判存（三审 dspro P1-3）；测试用全局租户口径（内置四技能在场）
        AssistantSkillHolder.set("t-zero-diff", "bogus-skill", "000000");
        assertThat(AssistantSkillHolder.get("t-zero-diff")).isNull();
        AssistantSkillHolder.set("t-zero-diff", "data-reading", "000000");
        assertThat(AssistantSkillHolder.get("t-zero-diff")).isEqualTo("data-reading");
        AssistantSkillHolder.set("t-zero-diff", null, "000000");
        assertThat(AssistantSkillHolder.get("t-zero-diff")).isNull();
        AssistantSkillHolder.set("t-zero-diff", "  ", "000000");
        assertThat(AssistantSkillHolder.get("t-zero-diff")).isNull();
    }
}
