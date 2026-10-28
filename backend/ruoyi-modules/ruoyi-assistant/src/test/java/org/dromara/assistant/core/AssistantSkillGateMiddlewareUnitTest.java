package org.dromara.assistant.core;

import io.agentscope.core.skill.AgentSkill;
import org.dromara.assistant.tools.ToolIdentity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 批翻译层护栏（2026-10-27 题3=甲）：矩阵行 → 可见技能 的纯函数判定正反例，
 * 以及「装载替换前后，同技能集对管理员/发布人/成员的可见清单 diff=0」的口径断言
 * （旧口径=内嵌表 minRole；新口径=SKILL.md minRole 经 filterVisibleImpl）。
 */
class AssistantSkillGateMiddlewareUnitTest {

    /** 造一个带 minRole 元数据的技能（不经文件系统，纯内存；官方元数据构造器） */
    private static AgentSkill skill(String name, String minRole) {
        return new AgentSkill(
            Map.of("name", name, "description", "desc-" + name, "minRole", minRole,
                "displayName", "显示-" + name, "triggers", List.of("词-" + name)),
            "body-" + name, Map.of(), "test");
    }

    @Test
    @Tag("dev")
    void builtinThresholdOnlyMatchesLegacyRoleFilter() {
        // 与旧 SkillAssets.catalog/body 的 minRole 口径逐角色对齐：diff=0 验收
        List<AgentSkill> raw = new ArrayList<>();
        for (SkillAssets.Skill s : SkillAssets.SKILLS) {
            raw.add(new AgentSkill(
                Map.of("name", s.id(), "description", s.description(),
                    "minRole", s.minRole() == ToolIdentity.LEVEL_ADMIN ? "admin"
                        : s.minRole() == ToolIdentity.LEVEL_PUBLISHER ? "publisher" : "member",
                    "displayName", s.name()),
                s.embeddedBody(), Map.of(), "test"));
        }
        for (int level : new int[]{ToolIdentity.LEVEL_MEMBER, ToolIdentity.LEVEL_PUBLISHER,
            ToolIdentity.LEVEL_ADMIN}) {
            List<String> visible = AssistantSkillGateMiddleware.filterVisibleImpl(raw, level, null)
                .stream().map(AgentSkill::getName).toList();
            List<String> legacy = SkillAssets.SKILLS.stream()
                .filter(s -> s.minRole() <= level).map(SkillAssets.Skill::id).toList();
            assertThat(visible).as("角色档 %d 可见技能与旧口径 diff=0", level).isEqualTo(legacy);
        }
    }

    @Test
    @Tag("dev")
    void gateOffHidesSkillForEveryone() {
        List<AgentSkill> raw = List.of(skill("data-reading", "member"));
        assertThat(AssistantSkillGateMiddleware.filterVisibleImpl(raw,
            ToolIdentity.LEVEL_ADMIN, Map.of("skill:data-reading", "off"))).isEmpty();
        // 未配行=内置门槛照常
        assertThat(AssistantSkillGateMiddleware.filterVisibleImpl(raw,
            ToolIdentity.LEVEL_MEMBER, Map.of())).hasSize(1);
    }

    @Test
    @Tag("dev")
    void gateRaisesThresholdNeverLowers() {
        List<AgentSkill> raw = List.of(skill("data-reading", "member"));
        // admin 档：成员不可见、管理员可见（只升不降）
        assertThat(AssistantSkillGateMiddleware.filterVisibleImpl(raw,
            ToolIdentity.LEVEL_MEMBER, Map.of("skill:data-reading", "admin"))).isEmpty();
        assertThat(AssistantSkillGateMiddleware.filterVisibleImpl(raw,
            ToolIdentity.LEVEL_ADMIN, Map.of("skill:data-reading", "admin"))).hasSize(1);
        // 内置高门槛不会被 member 行降到全员（与工具行同构）
        List<AgentSkill> publisherSkill = List.of(skill("urge-wording", "publisher"));
        assertThat(AssistantSkillGateMiddleware.filterVisibleImpl(publisherSkill,
            ToolIdentity.LEVEL_MEMBER, Map.of("skill:urge-wording", "member"))).isEmpty();
        assertThat(AssistantSkillGateMiddleware.filterVisibleImpl(publisherSkill,
            ToolIdentity.LEVEL_PUBLISHER, Map.of("skill:urge-wording", "member"))).hasSize(1);
    }

    @Test
    @Tag("dev")
    void invalidOrNullGateFallsBackToBuiltin() {
        List<AgentSkill> raw = List.of(skill("data-reading", "member"));
        assertThat(AssistantSkillGateMiddleware.filterVisibleImpl(raw,
            ToolIdentity.LEVEL_MEMBER, Map.of("skill:data-reading", "junk"))).hasSize(1);
        assertThat(AssistantSkillGateMiddleware.filterVisibleImpl(raw,
            ToolIdentity.LEVEL_MEMBER, null)).hasSize(1);
        assertThat(AssistantSkillGateMiddleware.gateKey("x")).isEqualTo("skill:x");
    }

    @Test
    @Tag("dev")
    void realClasspathSkillsVisiblePerRole() {
        // 真仓（classpath /skills 经 AssistantSkillSource 合并视图）跑一遍：成员只见
        // data-reading，发布人以上全见——与装载替换前完全一致；仓为空=classpath 构建故障，直接红
        List<AgentSkill> all = mergeViews();
        assertThat(all).as("classpath 技能仓必须装载出 4 技能").hasSize(4);
        List<String> member = AssistantSkillGateMiddleware.filterVisibleImpl(all,
            ToolIdentity.LEVEL_MEMBER, null).stream().map(AgentSkill::getName).toList();
        List<String> publisher = AssistantSkillGateMiddleware.filterVisibleImpl(all,
            ToolIdentity.LEVEL_PUBLISHER, null).stream().map(AgentSkill::getName).toList();
        assertThat(member).containsExactly("data-reading");
        assertThat(publisher).containsExactlyInAnyOrder("data-reading", "create-mission",
            "create-template", "urge-wording");
    }

    @Test
    @Tag("dev")
    void visibleToGatesInjectionAndSelfFetchPaths() {
        // 三审回修 P1-1 锚点：off/提档技能在注入装饰器与 view_skill 自取两条通道同样不可见
        Map<String, String> off = Map.of("skill:data-reading", "off");
        assertThat(AssistantSkillGateMiddleware.visibleTo("data-reading",
            ToolIdentity.LEVEL_ADMIN, off)).isFalse();
        assertThat(AssistantSkillGateMiddleware.visibleTo("data-reading",
            ToolIdentity.LEVEL_MEMBER, null)).isTrue();
        // 提档 admin：发布人不可见、管理员可见（只升不降同构）
        Map<String, String> raised = Map.of("skill:urge-wording", "admin");
        assertThat(AssistantSkillGateMiddleware.visibleTo("urge-wording",
            ToolIdentity.LEVEL_PUBLISHER, raised)).isFalse();
        assertThat(AssistantSkillGateMiddleware.visibleTo("urge-wording",
            ToolIdentity.LEVEL_ADMIN, raised)).isTrue();
        // 未知/空白 id 一律不可见
        assertThat(AssistantSkillGateMiddleware.visibleTo("bogus",
            ToolIdentity.LEVEL_ADMIN, null)).isFalse();
        assertThat(AssistantSkillGateMiddleware.visibleTo("  ",
            ToolIdentity.LEVEL_ADMIN, null)).isFalse();
    }

    private static List<AgentSkill> mergeViews() {
        // AssistantSkillSource.all() 是合并后的视图；重建 AgentSkill 仅为喂 filterVisibleImpl
        List<AgentSkill> out = new ArrayList<>();
        for (AssistantSkillSource.SkillView v : AssistantSkillSource.all()) {
            out.add(new AgentSkill(
                Map.of("name", v.id(), "description", v.description(),
                    "minRole", v.minRole() == 2 ? "admin" : v.minRole() == 1 ? "publisher" : "member",
                    "displayName", v.displayName()),
                v.body(), Map.of(), "test"));
        }
        return out;
    }
}
