package org.dromara.test.assistant;

import org.dromara.assistant.domain.DcsAssistantFeedback;
import org.dromara.assistant.service.AssistantConversationService;
import org.dromara.assistant.service.AssistantFeedbackService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批G G6 👍👎反馈单测（2026-10-06，Q13 轻量版）。
 * 提交 UP/DOWN；只能评本人会话消息（防跨用户刷评）；非法 rating 拒绝；租户列表可查。
 */
@Tag("p0probe")
@SpringBootTest
class G6FeedbackTest {

    private static final String RUN_KEY = "g6-" + System.currentTimeMillis();
    private static final Long U1 = 910000000000401L;
    private static final Long U2 = 910000000000402L;

    @Autowired
    AssistantFeedbackService feedbackService;
    @Autowired
    AssistantConversationService conversationService;

    @Test
    void submitRateAndList() {
        Long conv = conversationService.ensureConversation("987311", U1, RUN_KEY + "-t", "反馈");
        conversationService.appendUserMessage("987311", conv, U1, "这条回答不错");
        Long messageId = conversationService.listMessages("987311", U1, conv, 10, 0).get(0).getId();

        feedbackService.submit("987311", U1, messageId, "UP", null);
        feedbackService.submit("987311", U1, null, "DOWN", "第二条整会话差评");

        List<DcsAssistantFeedback> rows = feedbackService.listForTenant("987311", 50, 0);
        assertTrue(rows.stream().anyMatch(r -> "UP".equals(r.getRating()) && messageId.equals(r.getMessageId())),
            "点赞落库并关联消息");
        assertTrue(rows.stream().anyMatch(r -> "DOWN".equals(r.getRating()) && r.getMessageId() == null),
            "整会话点踩(无消息关联)落库");
    }

    @Test
    void foreignMessageRejected() {
        Long conv = conversationService.ensureConversation("987311", U1, RUN_KEY + "-t2", "归属");
        conversationService.appendUserMessage("987311", conv, U1, "别人的消息");
        Long messageId = conversationService.listMessages("987311", U1, conv, 10, 0).get(0).getId();
        ServiceException ex = assertThrows(ServiceException.class,
            () -> feedbackService.submit("987311", U2, messageId, "UP", null),
            "评他人消息必须 403");
        assertEquals(403, ex.getCode());
    }

    @Test
    void invalidRatingRejected() {
        assertThrows(ServiceException.class,
            () -> feedbackService.submit("987311", U1, null, "GOOD", null), "rating 仅 UP/DOWN");
    }

    /** AU-40(1008)：消息+评价 10 秒幂等窗——连点几下库内不重复（返回成功但跳过入库） */
    @Test
    void duplicateSubmitWithinWindowDeduped() {
        Long conv = conversationService.ensureConversation("987311", U1, RUN_KEY + "-t3", "幂等");
        conversationService.appendUserMessage("987311", conv, U1, "连点测试");
        Long messageId = conversationService.listMessages("987311", U1, conv, 10, 0).get(0).getId();

        feedbackService.submit("987311", U1, messageId, "DOWN", "答非所问");
        feedbackService.submit("987311", U1, messageId, "DOWN", "答非所问");

        long n = feedbackService.listForTenant("987311", 200, 0).stream()
            .filter(r -> messageId.equals(r.getMessageId()) && "DOWN".equals(r.getRating()))
            .count();
        assertEquals(1, n, "10 秒窗内同消息同评价只落一行（幂等窗去重）");
    }
}
