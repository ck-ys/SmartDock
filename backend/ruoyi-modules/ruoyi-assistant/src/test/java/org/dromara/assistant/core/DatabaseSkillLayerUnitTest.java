package org.dromara.assistant.core;

import io.agentscope.core.skill.AgentSkill;
import org.dromara.assistant.tools.ToolIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 5b 批数据库在线层护栏（题2 甲·三层叠加 / 多单位隔离）：
 * <ul>
 *   <li>层序：DB 行同名覆盖内置；DB 新增技能出现在合并视图</li>
 *   <li>租户隔离：A 单位的行 B 单位看不见；全局基线（000000）行两单位都见</li>
 *   <li>DB 层缺席（Spring 未就绪/表不可达）＝内置层照常，不连坐</li>
 * </ul>
 * 纯单测：primeForTest 直灌 DB 层缓存绕开 Spring；结束后全量失效防污染其它测试。
 */
class DatabaseSkillLayerUnitTest {

    private static final String T_A = "999901";
    private static final String T_B = "999902";

    @AfterEach
    void cleanup() {
        AssistantSkillSource.invalidateAll();
    }

    private static AgentSkill dbSkill(String id, String desc, int minRole) {
        return AgentSkill.builder()
            .name(id)
            .description(desc)
            .skillContent("# " + id + " 在线正文")
            // putMetadata 追加（.metadata 整体替换会抹掉 name/description 触发官方校验）
            .putMetadata("displayName", id)
            .putMetadata("minRole", String.valueOf(minRole))
            .source("database")
            .build();
    }

    @Test
    @Tag("dev")
    void tenantRowOverridesBuiltinAndAddsNewSkill() {
        // A 单位：覆写催办话术（描述改口）＋新增一个在线技能
        AssistantSkillSource.invalidateAll();
        DatabaseSkillRepository.primeForTest(T_A, List.of(
            dbSkill("urge-wording", "A 单位改过的催办口径", ToolIdentity.LEVEL_PUBLISHER),
            dbSkill("weekly-report", "A 单位在线新增的周报技能", ToolIdentity.LEVEL_MEMBER)));
        AssistantSkillSource.clearMergedOnly();

        List<AssistantSkillSource.SkillView> viewA = AssistantSkillSource.all(T_A);
        assertThat(viewA).extracting(AssistantSkillSource.SkillView::id)
            .contains("weekly-report", "urge-wording");
        assertThat(viewA.stream().filter(v -> v.id().equals("urge-wording")).findFirst().orElseThrow()
            .description()).isEqualTo("A 单位改过的催办口径");
        // 内置未被动到的技能照常在场
        assertThat(viewA).extracting(AssistantSkillSource.SkillView::id).contains("data-reading");
    }

    @Test
    @Tag("dev")
    void tenantIsolationHolds() {
        AssistantSkillSource.invalidateAll();
        // 2026-11-10 修过时断言：这里原本用 "weekly-report"，但它已是 classpath 内置技能，
        // 「B 不该看到 A 的行」被内置层自己顶成了红。换成任何内置层都没有的自造 id，
        // 才真正只考验「A 的数据库行不漏给 B」。
        DatabaseSkillRepository.primeForTest(T_A, List.of(
            dbSkill("a-only-skill", "A 单位在线新增的周报技能", ToolIdentity.LEVEL_MEMBER)));
        AssistantSkillSource.clearMergedOnly();

        // B 单位看不到 A 的行；B 视图仍有内置技能
        List<AssistantSkillSource.SkillView> viewB = AssistantSkillSource.all(T_B);
        assertThat(viewB).extracting(AssistantSkillSource.SkillView::id)
            .doesNotContain("a-only-skill")
            .contains("data-reading");
    }

    @Test
    @Tag("dev")
    void globalBaselineVisibleToAllTenants() {
        // 000000 全局基线行：A、B 两单位都见（题2 甲·000000=全员共用基线）。
        // 注意缓存语义：DB 层按租户缓存的是「该租户的生效集」——mapper 的
        // selectEffective 用 tenant_id IN ('000000', 租户) 在 SQL 里把全局行并入，
        // 纯单测无库，按同一语义给两个租户各灌含全局行的生效集。
        AssistantSkillSource.invalidateAll();
        AgentSkill global = dbSkill("global-skill", "全局基线技能", ToolIdentity.LEVEL_MEMBER);
        DatabaseSkillRepository.primeForTest(T_A, List.of(global));
        DatabaseSkillRepository.primeForTest(T_B, List.of(global));
        AssistantSkillSource.clearMergedOnly();

        assertThat(AssistantSkillSource.all(T_A)).extracting(AssistantSkillSource.SkillView::id)
            .contains("global-skill");
        assertThat(AssistantSkillSource.all(T_B)).extracting(AssistantSkillSource.SkillView::id)
            .contains("global-skill");
    }

    @Test
    @Tag("dev")
    void dbLayerAbsentMeansBuiltinOnly() {
        // 无 DB 行（层缺席）：视图退回内置层，不抛不缺。
        // 2026-11-10 修过时断言：原本拿 SkillAssets.SKILLS（退役常量表，只剩 4 条）当期望值，
        // 而 classpath 现在有 10 个技能目录——两边本来就不该相等。这里改成钉住语义本身：
        // 内置技能在、数据库行一条都没有。
        AssistantSkillSource.invalidateAll();
        List<AssistantSkillSource.SkillView> view = AssistantSkillSource.all(T_B);
        assertThat(view).extracting(AssistantSkillSource.SkillView::id)
            .contains("data-reading", "urge-wording")
            .doesNotContain("a-only-skill", "global-skill");
    }

    @Test
    @Tag("dev")
    void disabledRowMasksBuiltinEntirely() {
        // 5b 停用遮罩（活体首测 P0 回修）：disabled=1 行必须把内置层同名技能一并藏起，
        // 而不是被下层顶回——用户目录/管理列表/注入判定三条消费路径都吃 all() 视图
        AssistantSkillSource.invalidateAll();
        DatabaseSkillRepository.primeForTest(T_A, List.of(),
            java.util.Set.of("urge-wording"));
        AssistantSkillSource.clearMergedOnly();

        List<AssistantSkillSource.SkillView> viewA = AssistantSkillSource.all(T_A);
        assertThat(viewA).extracting(AssistantSkillSource.SkillView::id)
            .doesNotContain("urge-wording")
            .contains("data-reading");
        // 遮罩集对无停用行的租户无溢出
        AssistantSkillSource.clearMergedOnly();
        assertThat(AssistantSkillSource.all(T_B)).extracting(AssistantSkillSource.SkillView::id)
            .contains("urge-wording");
    }

    /* ===== 2026-11-10 Q3=乙：单条坏数据不得带走整层（可测缝 = DatabaseSkillRepository.toSkill） ===== */

    private static org.dromara.assistant.domain.DcsAssistantSkill row(String id, String desc) {
        org.dromara.assistant.domain.DcsAssistantSkill r = new org.dromara.assistant.domain.DcsAssistantSkill();
        r.setSkillId(id);
        r.setDescription(desc);
        r.setBody("# 正文");
        r.setMinRole("member");
        return r;
    }

    @Test
    @Tag("dev")
    @Tag("local")
    void toSkillRejectsEmptyNameAndEmptyDescription() {
        // 官方 Builder 的硬校验（must have name/description）就是「一条坏数据能让整层技能
        // 消失」的触发点：老代码只在外层 catch 兜，一抛就整层退场。这条把三个现实触发面钉死
        // ——空名称、描述为 null、描述为空串。
        assertThatThrownBy(() -> DatabaseSkillRepository.toSkill(row("", "一句说明")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatabaseSkillRepository.toSkill(row("ok-skill", null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatabaseSkillRepository.toSkill(row("ok-skill", "")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Tag("dev")
    @Tag("local")
    void toSkillKeepsValidRow() {
        AgentSkill s = DatabaseSkillRepository.toSkill(row("db-ok", "一句说明"));
        assertThat(s.getName()).isEqualTo("db-ok");
        assertThat(s.getDescription()).isEqualTo("一句说明");
        assertThat(s.getSkillContent()).isEqualTo("# 正文");
    }

    @Test
    @Tag("dev")
    @Tag("local")
    void failedCountIsZeroWhenLayerIsClean() {
        // 0 = 全部正常，界面此时一个字都不显示（不留「0 个失败」这种读数）
        DatabaseSkillRepository.primeForTest(T_A, List.of(dbSkill("x1", "说明", 0)), java.util.Set.of());
        assertThat(DatabaseSkillRepository.failedCount(T_A)).isZero();
    }
}
