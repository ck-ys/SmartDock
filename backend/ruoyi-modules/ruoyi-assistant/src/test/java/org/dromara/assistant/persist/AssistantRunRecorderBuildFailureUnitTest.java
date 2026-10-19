package org.dromara.assistant.persist;

import org.dromara.assistant.domain.DcsAssistantRun;
import org.dromara.assistant.limiter.AssistantDailyQuota;
import org.dromara.assistant.service.AssistantConversationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Q4/ISSUE-007①（2026-10-14）单元测试：工厂建会话失败的同步终局化通道。
 *
 * <p>覆盖口径：①正常终局化（条件化 FAILED + 按回读实际状态退回合）；②防双退（终态写
 * 0 行=已被兜底抢先终局化时不退）；③run 行不存在（resolver PG 闪断降级）静默跳过；
 * ④runId 空/白（旧客户端）无账可终；⑤reason 超 64 截断防 end_reason 列宽吞终态。
 * 纯 Mockito 不起 Spring——方法只触 ConversationService/DailyQuota 两个协作者。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AssistantRunRecorderBuildFailureUnitTest {

    @Mock
    private AssistantConversationService conversationService;

    @Mock
    private AssistantDailyQuota dailyQuota;

    @InjectMocks
    private AssistantRunRecorder recorder;

    private static DcsAssistantRun run(String status, String tenantId, Long userId) {
        DcsAssistantRun row = new DcsAssistantRun();
        row.setStatus(status);
        row.setTenantId(tenantId);
        row.setUserId(userId);
        return row;
    }

    @Test
    void 正常终局化_条件FAILED_按回读状态退款() {
        when(conversationService.finishRun("run-1", "FAILED", "agent-build-failed")).thenReturn(1);
        when(conversationService.runByRunId("run-1")).thenReturn(run("FAILED", "000000", 42L));

        recorder.finalizeBuildFailure("run-1", "agent-build-failed");

        verify(conversationService).finishRun("run-1", "FAILED", "agent-build-failed");
        verify(dailyQuota).refundTurn("000000", 42L);
    }

    @Test
    void 防双退_终态写零行_已被兜底抢先终局化时不退款() {
        when(conversationService.finishRun("run-2", "FAILED", "agent-build-failed")).thenReturn(0);
        when(conversationService.runByRunId("run-2")).thenReturn(run("INTERRUPTED", "000000", 42L));

        recorder.finalizeBuildFailure("run-2", "agent-build-failed");

        verify(conversationService).finishRun("run-2", "FAILED", "agent-build-failed");
        verify(dailyQuota, never()).refundTurn(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void run行不存在_静默跳过不退款() {
        when(conversationService.finishRun("run-3", "FAILED", "agent-build-failed")).thenReturn(1);
        when(conversationService.runByRunId("run-3")).thenReturn(null);

        recorder.finalizeBuildFailure("run-3", "agent-build-failed");

        verify(dailyQuota, never()).refundTurn(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void runId空白_无账可终_零交互() {
        recorder.finalizeBuildFailure("  ", "agent-build-failed");
        recorder.finalizeBuildFailure(null, "agent-build-failed");

        verifyNoInteractions(conversationService, dailyQuota);
    }

    @Test
    void reason超长截断_防end_reason列宽吞终态() {
        when(conversationService.finishRun(org.mockito.ArgumentMatchers.eq("run-4"),
            org.mockito.ArgumentMatchers.eq("FAILED"), anyString())).thenReturn(1);
        when(conversationService.runByRunId("run-4")).thenReturn(run("FAILED", "000000", 7L));
        String longReason = "x".repeat(100);

        recorder.finalizeBuildFailure("run-4", longReason);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conversationService).finishRun(org.mockito.ArgumentMatchers.eq("run-4"),
            org.mockito.ArgumentMatchers.eq("FAILED"), captor.capture());
        assertThat(captor.getValue()).hasSize(64);
        verify(dailyQuota).refundTurn("000000", 7L);
    }

    @Test
    void 终态回写抛异常_不连坐退款以外的补偿路径() {
        when(conversationService.finishRun("run-5", "FAILED", "agent-build-failed"))
            .thenThrow(new RuntimeException("pg down"));
        when(conversationService.runByRunId("run-5")).thenReturn(run("FAILED", "000000", 9L));

        recorder.finalizeBuildFailure("run-5", "agent-build-failed");

        // 回写失败但回读仍见 FAILED（如重试后成功）→ 退款照走；异常本身被吞不影响调用方
        verify(dailyQuota).refundTurn("000000", 9L);
    }
}
