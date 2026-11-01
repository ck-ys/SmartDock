package org.dromara.test.assistant;

import org.dromara.assistant.core.AssistantPlanSignal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多实例批 4：计划卡 planId 幂等（2026-11-01）。
 *
 * <p>没有 planId 时，两张旧卡/两个标签页同时点「直接执行」可能各自把 pending 消费掉，
 * 后端就会多跑一轮。这里钉死：带错 planId 不消费、同一 planId 只能成功一次。</p>
 */
@Tag("local")
class AssistantPlanSignalMultiInstanceTest {

    @Test
    void planIdMakesConfirmExactlyOnce() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        signal.submit("th-mi-1", "000000", 1L, "批处理", List.of("读数据", "写结果"));

        AssistantPlanSignal.PlanState pending = signal.state("th-mi-1");
        assertEquals("pending", pending.status());
        assertNotNull(pending.planId(), "submit 必须生成 planId");

        assertFalse(signal.decide("th-mi-1", "000000", 1L, "old-plan-id", "confirmed"),
            "旧卡 planId 不匹配，不得消费");
        assertTrue(signal.isPending("th-mi-1"), "错误 planId 不能把闸清掉");

        assertTrue(signal.decide("th-mi-1", "000000", 1L, pending.planId(), "confirmed"));
        assertFalse(signal.isPending("th-mi-1"));
        assertEquals("confirmed", signal.state("th-mi-1").status());
        assertEquals(pending.planId(), signal.state("th-mi-1").planId());

        assertFalse(signal.decide("th-mi-1", "000000", 1L, pending.planId(), "confirmed"),
            "第二次抢点必须失败");
    }

    @Test
    void expiredPlanCannotBeConfirmed() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        signal.submit("th-mi-2", "000000", 1L, "过期计划", List.of("一步"), 0L);

        AssistantPlanSignal.PlanState st = signal.state("th-mi-2");
        assertEquals("expired", st.status());
        assertFalse(signal.isPending("th-mi-2"), "过期后写操作不再被锁");
        assertFalse(signal.decide("th-mi-2", "000000", 1L, st.planId(), "confirmed"),
            "过期卡点确认不得再执行");
    }

    @Test
    void ownerMismatchCannotConsume() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        signal.submit("th-mi-3", "000000", 1L, "归属校验", List.of("一步"));
        String planId = signal.state("th-mi-3").planId();

        assertFalse(signal.decide("th-mi-3", "000000", 2L, planId, "confirmed"),
            "别人不能确认我的计划");
        assertFalse(signal.decide("th-mi-3", "999999", 1L, planId, "confirmed"),
            "别的租户不能确认我的计划");
        assertTrue(signal.isPending("th-mi-3"), "归属不符不得清闸");
    }
}
