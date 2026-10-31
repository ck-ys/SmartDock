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
}
