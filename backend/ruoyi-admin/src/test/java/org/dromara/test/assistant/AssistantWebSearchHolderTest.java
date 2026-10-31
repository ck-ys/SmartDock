package org.dromara.test.assistant;

import org.dromara.assistant.core.AssistantWebSearchHolder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题10 案1（2026-10-31）：联网搜索默认可用、可停用。
 *
 * <p>口径反转的核心风险有两侧：①默认值写反——老会话没带参数就整块搜不了；
 * ②停用判定太宽——把 null/乱填也当停用，等于静默关闸。本测试把两侧钉死。</p>
 */
@Tag("local")
class AssistantWebSearchHolderTest {

    @Test
    void defaultIsOnWhenNeverTouched() {
        assertTrue(AssistantWebSearchHolder.isOn("q10-t1"), "没登记过的线程默认可用（模型自主判断）");
        assertFalse(AssistantWebSearchHolder.isOn(null), "无 thread 不判定为可用（无会话可挂）");
    }

    @Test
    void explicitFalseStopsAndClearsOnTrueOrDefault() {
        AssistantWebSearchHolder.set("q10-t2", false);
        assertFalse(AssistantWebSearchHolder.isOn("q10-t2"), "boolean false 必须停用");
        AssistantWebSearchHolder.set("q10-t2", "0");
        assertFalse(AssistantWebSearchHolder.isOn("q10-t2"), "字符串 0 必须停用");
        AssistantWebSearchHolder.set("q10-t2", "no");
        assertFalse(AssistantWebSearchHolder.isOn("q10-t2"), "no 必须停用");
        AssistantWebSearchHolder.set("q10-t2", "OFF");
        assertFalse(AssistantWebSearchHolder.isOn("q10-t2"), "OFF（忽略大小写）必须停用");

        AssistantWebSearchHolder.set("q10-t2", true);
        assertTrue(AssistantWebSearchHolder.isOn("q10-t2"), "显式 true 恢复可用");
        AssistantWebSearchHolder.set("q10-t2", false);
        AssistantWebSearchHolder.set("q10-t2", null);
        assertFalse(AssistantWebSearchHolder.isOn("q10-t2"),
            "缺省(null)不改现状——旧前端不带键不得把显式停用偷偷改回联网");
        AssistantWebSearchHolder.set("q10-t2", "whatever");
        assertTrue(AssistantWebSearchHolder.isOn("q10-t2"), "乱填不放宽成停用（只有显式假值算停用）");
    }

    @Test
    void needsRebuildOnlyAfterFlipFromBuiltBaseline() {
        assertFalse(AssistantWebSearchHolder.needsRebuild("q10-t3"), "从未建过 agent=无缓存可失效");
        AssistantWebSearchHolder.markBuilt("q10-t3", true);
        assertFalse(AssistantWebSearchHolder.needsRebuild("q10-t3"), "基准与实际一致不重建");
        AssistantWebSearchHolder.set("q10-t3", false);
        assertTrue(AssistantWebSearchHolder.needsRebuild("q10-t3"), "停用翻转必须触发重建（撤工具集）");
        AssistantWebSearchHolder.markBuilt("q10-t3", false);
        assertFalse(AssistantWebSearchHolder.needsRebuild("q10-t3"), "重建后基准对齐");
        AssistantWebSearchHolder.set("q10-t3", true);
        assertTrue(AssistantWebSearchHolder.needsRebuild("q10-t3"), "恢复可用同样要重建（挂回工具集）");
    }
}
