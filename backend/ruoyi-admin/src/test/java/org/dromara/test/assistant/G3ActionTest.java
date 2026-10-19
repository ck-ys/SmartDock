package org.dromara.test.assistant;

import org.dromara.assistant.domain.DcsAssistantAction;
import org.dromara.assistant.service.AssistantActionService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批G G3 动作账本单测（2026-10-06）。幂等键三口径：
 * 首开=created EXECUTING；DONE 重放=created false（ActTools 复述回执）；
 * 在途重入=created false + EXECUTING（ActTools 拒绝重复触发）；异主键=403。
 */
@Tag("p0probe")
@SpringBootTest
class G3ActionTest {

    private static final String RUN_KEY = "g3-" + System.currentTimeMillis();
    private static final Long U1 = 910000000000201L;
    private static final Long U2 = 910000000000202L;

    @Autowired
    AssistantActionService actionService;

    @Test
    void ledgerLifecycleAndReplay() {
        String key = RUN_KEY + "-a";
        AssistantActionService.OpenResult first = actionService.openExecution(
            "987311", U1, RUN_KEY + "-run", RUN_KEY + "-th", "act_remind", "{\"missionId\":1}", key);
        assertTrue(first.created(), "首开=新建行");
        assertEquals("EXECUTING", first.row().getStatus());

        actionService.markDone(first.row().getId(), "催办已发送（3 人）");

        AssistantActionService.OpenResult replay = actionService.openExecution(
            "987311", U1, RUN_KEY + "-run", RUN_KEY + "-th", "act_remind", "{\"missionId\":1}", key);
        assertFalse(replay.created(), "同键重放不新建");
        assertEquals("DONE", replay.row().getStatus(), "DONE 重放=ActTools 复述回执的依据");
        assertEquals("催办已发送（3 人）", replay.row().getResultSummary());
    }

    @Test
    void inFlightReplayRejectedShape() {
        String key = RUN_KEY + "-b";
        actionService.openExecution("987311", U1, RUN_KEY + "-run2", null, "act_remind", "{}", key);
        AssistantActionService.OpenResult second = actionService.openExecution(
            "987311", U1, RUN_KEY + "-run2", null, "act_remind", "{}", key);
        assertFalse(second.created());
        assertEquals("EXECUTING", second.row().getStatus(), "在途重入=ActTools DUPLICATED 拒绝的依据");
    }

    @Test
    void crossUserKeyRejected() {
        String key = RUN_KEY + "-c";
        actionService.openExecution("987311", U1, RUN_KEY + "-run3", null, "act_remind", "{}", key);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> actionService.openExecution("987311", U2, RUN_KEY + "-run3", null, "act_remind", "{}", key),
            "异主撞键必须 403");
        assertEquals(403, ex.getCode());
    }

    @Test
    void listForUserOwnershipScoped() {
        String key = RUN_KEY + "-d";
        actionService.openExecution("987311", U1, RUN_KEY + "-run4", RUN_KEY + "-th4", "act_remind", "{}", key);
        actionService.markDone(actionService.openExecution(
            "987311", U1, RUN_KEY + "-run4", RUN_KEY + "-th4", "act_remind", "{}", key).row().getId(), "ok");
        List<DcsAssistantAction> mine = actionService.listForUser("987311", U1, RUN_KEY + "-run4", null);
        assertEquals(1, mine.size(), "按 runId 查本人账本");
        assertEquals(0, actionService.listForUser("987311", U2, RUN_KEY + "-run4", null).size(),
            "异主查不到（归属谓词）");
        assertEquals(1, actionService.listForUser("987311", U1, null, RUN_KEY + "-th4").size(),
            "按 threadId 查（断线恢复通道）");
    }
}
