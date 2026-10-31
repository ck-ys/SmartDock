package org.dromara.test.assistant;

import org.dromara.assistant.core.AssistantAutonomyHolder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题9 案1（2026-10-31）：自主权三档的判定语义。
 *
 * <p>这层直接决定写类工具要不要弹确认卡——判错一侧是「用户选了全自动却还被拦」，
 * 另一侧更严重：「没选过全自动却被静默放行」（伪造/乱填参数放宽权限）。两侧都钉住。</p>
 */
@Tag("local")
class AssistantAutonomyHolderTest {

    @Test
    void defaultIsAlwaysAskAndInvalidValuesFallBack() {
        assertEquals(AssistantAutonomyHolder.ALWAYS, AssistantAutonomyHolder.level("q9-t1"), "默认每次都问");
        AssistantAutonomyHolder.set("q9-t1", "auto");
        assertEquals(AssistantAutonomyHolder.AUTO, AssistantAutonomyHolder.level("q9-t1"));
        AssistantAutonomyHolder.set("q9-t1", "AUTO ");
        assertEquals(AssistantAutonomyHolder.AUTO, AssistantAutonomyHolder.level("q9-t1"), "忽略大小写/空格");
        AssistantAutonomyHolder.set("q9-t1", "yolo");
        assertEquals(AssistantAutonomyHolder.ALWAYS, AssistantAutonomyHolder.level("q9-t1"), "非法值不得放宽权限");
        AssistantAutonomyHolder.set("q9-t1", null);
        assertEquals(AssistantAutonomyHolder.ALWAYS, AssistantAutonomyHolder.level("q9-t1"));
        assertEquals(AssistantAutonomyHolder.ALWAYS, AssistantAutonomyHolder.level(null));
    }

    @Test
    void alwaysNeverAutoAllowsEvenIfApproved() {
        AssistantAutonomyHolder.set("q9-t2", "always");
        AssistantAutonomyHolder.rememberApproved("q9-t2", "dcs_remind_create");
        assertFalse(AssistantAutonomyHolder.autoAllows("q9-t2", "dcs_remind_create"), "每次都问=永远不自动放行");
    }

    @Test
    void onceOnlyAutoAllowsSameToolAfterApproval() {
        AssistantAutonomyHolder.set("q9-t3", "once");
        assertFalse(AssistantAutonomyHolder.autoAllows("q9-t3", "dcs_remind_create"), "没确认过=仍要问");
        AssistantAutonomyHolder.rememberApproved("q9-t3", "dcs_remind_create");
        assertTrue(AssistantAutonomyHolder.autoAllows("q9-t3", "dcs_remind_create"), "同类确认过=下一轮放行");
        assertFalse(AssistantAutonomyHolder.autoAllows("q9-t3", "dcs_task_create"), "按工具名记键，别的写工具仍要问");
    }

    @Test
    void autoAllowsEveryWriteTool() {
        AssistantAutonomyHolder.set("q9-t4", "auto");
        assertTrue(AssistantAutonomyHolder.autoAllows("q9-t4", "dcs_remind_create"));
        assertTrue(AssistantAutonomyHolder.autoAllows("q9-t4", "dcs_task_create"));
        assertFalse(AssistantAutonomyHolder.autoAllows(null, "dcs_task_create"), "无会话不自动放行");
    }

    /** 题9 三席回修 P1：once 档确认一次后必须触发缓存重建（否则规则停在 ASK，「同类只问一次」刷新即退化） */
    @Test
    void approvalBumpsGenerationAndTriggersRebuild() {
        AssistantAutonomyHolder.set("q9-t6", "once");
        AssistantAutonomyHolder.markBuilt("q9-t6", AssistantAutonomyHolder.ONCE);
        assertFalse(AssistantAutonomyHolder.needsRebuild("q9-t6"), "刚建好不重建");
        AssistantAutonomyHolder.rememberApproved("q9-t6", "dcs_remind_create");
        assertTrue(AssistantAutonomyHolder.needsRebuild("q9-t6"), "批准一次就要重算权限规则（档位没变也一样）");
        AssistantAutonomyHolder.markBuilt("q9-t6", AssistantAutonomyHolder.ONCE);
        assertFalse(AssistantAutonomyHolder.needsRebuild("q9-t6"), "重建后基准对齐");
        AssistantAutonomyHolder.rememberApproved("q9-t6", "dcs_task_create");
        assertTrue(AssistantAutonomyHolder.needsRebuild("q9-t6"), "再批准另一个工具同样要重建");
    }

    @Test
    void rebuildOnlyWhenLevelDiffersFromBuildBaseline() {
        assertFalse(AssistantAutonomyHolder.needsRebuild("q9-t5"), "从未建过=无缓存可失效");
        AssistantAutonomyHolder.markBuilt("q9-t5", AssistantAutonomyHolder.ALWAYS);
        assertFalse(AssistantAutonomyHolder.needsRebuild("q9-t5"), "档位未变不重建");
        AssistantAutonomyHolder.set("q9-t5", "auto");
        assertTrue(AssistantAutonomyHolder.needsRebuild("q9-t5"), "档位翻转必须重建（权限规则建时固化）");
        AssistantAutonomyHolder.markBuilt("q9-t5", AssistantAutonomyHolder.AUTO);
        assertFalse(AssistantAutonomyHolder.needsRebuild("q9-t5"));
    }
}
