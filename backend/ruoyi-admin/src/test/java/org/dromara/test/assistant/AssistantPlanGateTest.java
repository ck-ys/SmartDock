package org.dromara.test.assistant;

import org.dromara.assistant.core.AssistantPlanGateMiddleware;
import org.dromara.assistant.core.AssistantPlanSignal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题8 案1（2026-10-31）：计划待确认态的生命周期。
 *
 * <p>硬闸判定只看这个开关：submit_plan 置真；清闸只认用户动作（点「直接执行/改计划/
 * 取消」随请求上报 planDecision、新 submit_plan 覆盖）+ 30 分钟 TTL 兜底——
 * <b>不按回合边界清</b>（用户放着计划卡不点、随口聊别的，写操作必须继续被拒）。
 * 置错会出两种事故——一边是计划期写操作照跑（闸没生效），另一边是计划确认后
 * 写操作还被拒（闸没放开）。</p>
 */
@Tag("local")
class AssistantPlanGateTest {

    @Test
    void pendingLifecycleFollowsSubmitAndRunEnd() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        assertFalse(signal.isPending("th-1"), "初始不在计划态");

        // ②（2026-10-31）：submit/clearPending 补 owner 实参（tenantId+userId），断言语义不变
        signal.submit("th-1", "000000", 1L, "本周复核周报", List.of("汇总复核", "记住偏好", "出周报"));
        assertTrue(signal.isPending("th-1"), "submit_plan 后进入待确认态");
        assertFalse(signal.isPending("th-2"), "其它线程不受影响（会话隔离）");

        signal.clearPending("th-1", "000000", 1L);
        assertFalse(signal.isPending("th-1"), "回合结束/确认后放开");
    }

    @Test
    void emptySubmitDoesNotArmGate() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        signal.submit("th-3", "000000", 1L, null, List.of());
        assertFalse(signal.isPending("th-3"), "空计划不置闸（submit_plan 自身会返回错误）");
        signal.submit(null, "000000", 1L, "x", List.of("a"));
        signal.submit(" ", "000000", 1L, "x", List.of("a"));
        assertFalse(signal.isPending(null));
    }

    @Test
    void planControlToolsAreAlwaysAllowed() {
        assertTrue(AssistantPlanGateMiddleware.ALWAYS_ALLOWED.contains("submit_plan"));
        assertTrue(AssistantPlanGateMiddleware.ALWAYS_ALLOWED.contains("todo_write"));
        assertFalse(AssistantPlanGateMiddleware.ALWAYS_ALLOWED.contains("save_memory"),
            "写类工具不在白名单——判定只读与否必须真正生效");
    }
}
