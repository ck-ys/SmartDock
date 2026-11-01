package org.dromara.assistant.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 三批③（2026-11-01）：放行方式判定纯函数单测。
 *
 * <p>护栏重点：①四分流（save_memory→allow / 快照外→confirm / 快照内+auto→auto /
 * 快照内+once→once）；②save_memory 在 auto 档仍 allow（平台放行优先于档位）；
 * ③always 档经确认卡放行（autonomyAllowedAtBuild=false）必记 confirm——
 * 不误记 auto/once（若误用 writeTools 全集当参数，confirm 路径全错）。</p>
 */
@Tag("local")
class AssistantReleaseModeTest {

    @Test
    void resolveFourWaySplit() {
        assertEquals(AssistantReleaseMode.ALLOW, AssistantReleaseMode.resolve("save_memory", true, AssistantAutonomyHolder.AUTO),
            "save_memory 平台放行优先（在 auto 档且在放行集里也记 allow）");
        assertEquals(AssistantReleaseMode.ALLOW, AssistantReleaseMode.resolve("save_memory", false, AssistantAutonomyHolder.ALWAYS),
            "save_memory 恒 allow（不在放行集也记 allow——平台注册即 ALLOW）");
        assertEquals(AssistantReleaseMode.CONFIRM, AssistantReleaseMode.resolve("dcs_create_task", false, AssistantAutonomyHolder.ALWAYS),
            "快照外=ASK 规则经确认卡放行→confirm");
        assertEquals(AssistantReleaseMode.AUTO, AssistantReleaseMode.resolve("dcs_create_task", true, AssistantAutonomyHolder.AUTO),
            "快照内+auto 档→auto");
        assertEquals(AssistantReleaseMode.ONCE, AssistantReleaseMode.resolve("dcs_create_task", true, AssistantAutonomyHolder.ONCE),
            "快照内+once 档→once（同类已确认）");
    }

    @Test
    void alwaysLevelConfirmReleaseNeverMisrecordedAsAutoOrOnce() {
        // always 档：写类工具不在建时放行集（autoAllows=false），经确认卡放行必记 confirm
        String mode = AssistantReleaseMode.resolve("dcs_create_task", false, AssistantAutonomyHolder.ALWAYS);
        assertEquals(AssistantReleaseMode.CONFIRM, mode);
        org.junit.jupiter.api.Assertions.assertNotEquals(AssistantReleaseMode.AUTO, mode, "不得误记 auto");
        org.junit.jupiter.api.Assertions.assertNotEquals(AssistantReleaseMode.ONCE, mode, "不得误记 once");
    }

    @Test
    void displayMappingCoversFourModesAndEmpty() {
        assertEquals("确认后执行", AssistantReleaseMode.display(AssistantReleaseMode.CONFIRM));
        assertEquals("同类已确认", AssistantReleaseMode.display(AssistantReleaseMode.ONCE));
        assertEquals("全自动", AssistantReleaseMode.display(AssistantReleaseMode.AUTO));
        assertEquals("平台放行", AssistantReleaseMode.display(AssistantReleaseMode.ALLOW));
        assertEquals("—", AssistantReleaseMode.display(null), "拒绝行/历史行（空值）显示长破折");
        assertEquals("—", AssistantReleaseMode.display(""), "空串同空值");
        assertEquals("legacy-code", AssistantReleaseMode.display("legacy-code"), "未知码原样返回（不猜）");
    }
}
