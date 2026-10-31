package org.dromara.test.assistant;

import com.smartdock.domain.DcsAiConfig;
import org.dromara.assistant.core.AssistantModelDegradeSignal;
import org.dromara.assistant.factory.AssistantModelFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 题5 案1（2026-10-31）：模型降级提示的载体契约。
 *
 * <p>守两件事：①信号「一线程一条、取走即清」——避免同一条提示反复弹；
 * ②提示里的模型名兜底绝不出现 null（配置名空时退配置号）。</p>
 */
@Tag("local")
class AssistantModelDegradeSignalTest {

    @Test
    void signalIsOneShotPerThread() {
        AssistantModelDegradeSignal signal = new AssistantModelDegradeSignal();
        signal.submit("thread-a", "你选的模型当前不可用，本回合改用「m2」回答");
        signal.submit("thread-a", "第二条覆盖第一条");

        assertEquals("第二条覆盖第一条", signal.take("thread-a"), "同线程后写覆盖前写");
        assertNull(signal.take("thread-a"), "取走即清，不重复提示");
        assertNull(signal.take(null), "空 thread 不炸");
        assertNull(signal.take(" "), "空白 thread 不炸");
        signal.submit(null, "x");
        signal.submit("thread-b", " ");
        assertNull(signal.take("thread-b"), "空提示不入仓");
    }

    @Test
    void modelLabelNeverNull() {
        assertEquals("默认模型", AssistantModelFactory.modelLabel(null));

        DcsAiConfig blank = new DcsAiConfig();
        blank.setConfigId(42L);
        assertEquals("配置 #42", AssistantModelFactory.modelLabel(blank), "模型名空走配置号");

        DcsAiConfig named = new DcsAiConfig();
        named.setConfigId(7L);
        named.setModelName("deepseek-v4");
        assertEquals("deepseek-v4", AssistantModelFactory.modelLabel(named));
    }
}
