package org.dromara.test.assistant;

import org.dromara.assistant.core.AssistantSkillUsageMiddleware;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 题2 案3（2026-10-31）：技能使用统计的参数解析契约。
 *
 * <p>两代工具参数名不同——官方 {@code load_skill_through_path} 用 skillId，
 * 遗留 {@code view_skill} 用 skill_id；解析错=统计记到空技能或根本不记。</p>
 */
@Tag("local")
class AssistantSkillUsageMiddlewareTest {

    @Test
    void parsesOfficialAndLegacySkillTools() {
        assertEquals("report-weekly", AssistantSkillUsageMiddleware.skillIdOf(
            "load_skill_through_path", Map.of("skillId", "report-weekly", "path", "SKILL.md")));
        assertEquals("report-weekly", AssistantSkillUsageMiddleware.skillIdOf(
            "view_skill", Map.of("skill_id", " report-weekly ")));
    }

    @Test
    void toleratesSwappedKeysAndMissingValues() {
        // 参数名写反也认（模型偶发按别名传）
        assertEquals("x", AssistantSkillUsageMiddleware.skillIdOf(
            "load_skill_through_path", Map.of("skill_id", "x")));
        assertEquals("y", AssistantSkillUsageMiddleware.skillIdOf("view_skill", Map.of("skillId", "y")));

        assertNull(AssistantSkillUsageMiddleware.skillIdOf("load_skill_through_path", Map.of("path", "SKILL.md")));
        assertNull(AssistantSkillUsageMiddleware.skillIdOf("web_search", Map.of("query", "x")));
        assertNull(AssistantSkillUsageMiddleware.skillIdOf(null, Map.of("skillId", "x")));
        assertNull(AssistantSkillUsageMiddleware.skillIdOf("view_skill", null));
        assertNull(AssistantSkillUsageMiddleware.skillIdOf("view_skill", Map.of("skill_id", "  ")));
    }

    @Test
    void skipsDoubleCountWhenManualPickAlreadyRecorded() {
        // 手动胶囊已由 resolver 记过同一技能 → 工具再取不重复记账
        org.junit.jupiter.api.Assertions.assertFalse(
            AssistantSkillUsageMiddleware.shouldRecordToolUse("report-weekly", "report-weekly"));
        // 未手动选、或模型自取的是另一个技能 → 照记
        org.junit.jupiter.api.Assertions.assertTrue(
            AssistantSkillUsageMiddleware.shouldRecordToolUse(null, "report-weekly"));
        org.junit.jupiter.api.Assertions.assertTrue(
            AssistantSkillUsageMiddleware.shouldRecordToolUse("report-weekly", "sql-review"));
    }

    @Test
    void hitlResumeIsNotANewManualPick() {
        // HITL 确认/取消的补跑仍带 skillId：hasResume=true 不能再记一次
        org.junit.jupiter.api.Assertions.assertFalse(
            AssistantSkillUsageMiddleware.shouldRecordManualPick(true));
        org.junit.jupiter.api.Assertions.assertTrue(
            AssistantSkillUsageMiddleware.shouldRecordManualPick(false));
    }
}
