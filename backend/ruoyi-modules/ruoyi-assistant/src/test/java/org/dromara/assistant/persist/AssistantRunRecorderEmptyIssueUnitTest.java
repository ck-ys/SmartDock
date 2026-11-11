package org.dromara.assistant.persist;

import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import org.dromara.assistant.billing.AssistantCostLedger;
import org.dromara.assistant.core.AssistantInstanceContext;
import org.dromara.assistant.limiter.AssistantDailyQuota;
import org.dromara.assistant.security.AssistantPiiFilter;
import org.dromara.assistant.service.AssistantConversationService;
import org.dromara.assistant.service.AssistantFollowupService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-11 拍板 Q4-2：正常收尾但整轮零正文零工具 → 落 empty 问题行的链路单测。
 *
 * <p>empty 码活体难稳定触发（要服务商真吐空轮），此处用 AGUI 帧直喂 recorder 钉死
 * 判据三档：①空增量帧+END（buffer 存在但 isEmpty，最常见的真实空轮形态）→ empty；
 * ②有正文 → 不落；③只出过工具 → 不落。喂帧走 onEvents 正门（含 RUN_FINISHED
 * 终态异步写），不是直捅私有方法——判据若回归（标记时机/清跟踪次序）这里会先红。</p>
 */
@Tag("dev")
@Tag("local")
@ExtendWith(MockitoExtension.class)
class AssistantRunRecorderEmptyIssueUnitTest {

    @Mock
    private AssistantConversationService conversationService;
    @Mock
    private AssistantDailyQuota dailyQuota;
    @Mock
    private AssistantPendingInterruptCache pendingInterrupts;
    @Mock
    private AssistantPiiFilter piiFilter;
    @Mock
    private AssistantFollowupService followupService;
    @Mock
    private AssistantInstanceContext instanceContext;
    @Mock
    private AssistantCostLedger costLedger;

    @InjectMocks
    private AssistantRunRecorder recorder;

    @Mock
    private AguiStreamContext context;

    private void play(String runId, String threadId, AguiEvent... events) {
        when(context.getRunId()).thenReturn(runId);
        when(context.getThreadId()).thenReturn(threadId);
        recorder.onEvents(context, List.of(events));
    }

    @Test
    void 空增量帧正常收尾_落empty问题行() {
        when(conversationService.finishRun("run-e1", "COMPLETED", "normal")).thenReturn(1);

        // 服务商真吐空轮的形态：CONTENT 帧来了但 delta 为空 → buffer isEmpty 不算产出
        play("run-e1", "th-e1",
            new AguiEvent.TextMessageContent("th-e1", "run-e1", "m1", ""),
            new AguiEvent.TextMessageEnd("th-e1", "run-e1", "m1"),
            new AguiEvent.RunFinished("th-e1", "run-e1", null,
                new AguiEvent.RunFinishedSuccessOutcome()));

        verify(conversationService, timeout(3000)).recordRunIssue("run-e1", "empty", null);
        verify(conversationService, timeout(3000)).finishRun("run-e1", "COMPLETED", "normal");
    }

    @Test
    void 有正文的轮次_不落empty() throws InterruptedException {
        when(conversationService.finishRun("run-e2", "COMPLETED", "normal")).thenReturn(1);

        play("run-e2", "th-e2",
            new AguiEvent.TextMessageContent("th-e2", "run-e2", "m1", "hi"),
            new AguiEvent.TextMessageEnd("th-e2", "run-e2", "m1"),
            new AguiEvent.RunFinished("th-e2", "run-e2", null,
                new AguiEvent.RunFinishedSuccessOutcome()));

        // 先等终态写到位，再留一小窗让同异步块内紧随其后的问题行路径（若有）跑完
        verify(conversationService, timeout(3000)).finishRun("run-e2", "COMPLETED", "normal");
        Thread.sleep(300);
        verify(conversationService, never()).recordRunIssue(eq("run-e2"), anyString(), anyString());
        verify(conversationService, never()).recordRunIssue(eq("run-e2"), eq("empty"), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void 只出过工具的轮次_不落empty() throws InterruptedException {
        when(conversationService.finishRun("run-e3", "COMPLETED", "normal")).thenReturn(1);

        play("run-e3", "th-e3",
            new AguiEvent.ToolCallStart("th-e3", "run-e3", "tc1", "web_search"),
            new AguiEvent.RunFinished("th-e3", "run-e3", null,
                new AguiEvent.RunFinishedSuccessOutcome()));

        verify(conversationService, timeout(3000)).finishRun("run-e3", "COMPLETED", "normal");
        Thread.sleep(300);
        verify(conversationService, never()).recordRunIssue(eq("run-e3"), anyString(), anyString());
        verify(conversationService, never()).recordRunIssue(eq("run-e3"), eq("empty"), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void 迟到终态被丢弃_不叠empty() throws InterruptedException {
        // 收割器已抢先终局化（rows=0）→ 不得再叠问题行（Q1-3 收口规则）
        when(conversationService.finishRun("run-e4", "COMPLETED", "normal")).thenReturn(0);

        play("run-e4", "th-e4",
            new AguiEvent.RunFinished("th-e4", "run-e4", null,
                new AguiEvent.RunFinishedSuccessOutcome()));

        verify(conversationService, timeout(3000)).finishRun("run-e4", "COMPLETED", "normal");
        Thread.sleep(300);
        verify(conversationService, never()).recordRunIssue(eq("run-e4"), anyString(), anyString());
        verify(conversationService, never()).recordRunIssue(eq("run-e4"), eq("empty"), org.mockito.ArgumentMatchers.isNull());
    }
}
