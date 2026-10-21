package org.dromara.assistant.persist;

import org.dromara.assistant.billing.AssistantCostLedger;
import org.dromara.assistant.domain.DcsAssistantRun;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ISSUE-004/005（2026-10-14）单元测试：批6-#2 增量冲刷+失联收割链路。
 *
 * <p>005 测试接缝（Clock 外参 flushAndReap(now) + 五张追踪表包内可见）让收割/节流
 * 判定可拨针驱动，不睡墙钟。覆盖口径：①失联收割终局化（锚转 truncated+条件终态+退
 * 回合+跟踪清理）；②在飞豁免（模型调用中停滞不误杀）；③ISSUE-002 回归锁——收割 DB
 * 失败不回填伪造停滞时刻（重试轮误收割活 run 的根因）+锚放回等重试；④FLUSH_MS 节流
 * （窗内不空写/过节流原地更新 partial）；⑤无锚失联不补插行（dspro P0-1②口径）。
 * 纯 Mockito 不起 Spring。</p>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AssistantRunRecorderReapFlushUnitTest {

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
    private AssistantCostLedger costLedger;

    @InjectMocks
    private AssistantRunRecorder recorder;

    /** 拨针基准时刻（任意值；与墙钟解耦是本组测试存在的前提） */
    private static final long NOW = 1_760_000_000_000L;
    private static final long STALL = 60_000L;

    private static DcsAssistantRun run(String tenantId, Long userId) {
        return run(tenantId, userId, null);
    }

    /** glm53 P2-1 回修：首插路径 conversationByThread 依赖 run.threadId——不 set 则
     *  conv 查不到提前 return，appendAssistantMessage 永不触发（新用例必红的根因） */
    private static DcsAssistantRun run(String tenantId, Long userId, String threadId) {
        DcsAssistantRun row = new DcsAssistantRun();
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setThreadId(threadId);
        return row;
    }

    /** 播种一个 run 的跟踪态（内容缓冲+步骤键+可选 partial 锚）；停滞时刻单独覆写 */
    private void seedRun(String runId, String threadId, String content, Long messageId) {
        recorder.lastEventAt.put(runId, NOW - 1_000L);
        recorder.runStepKeys.put(runId, threadId);
        recorder.buffers.computeIfAbsent(runId, k -> new java.util.concurrent.ConcurrentHashMap<>())
            .put("m-1", new StringBuilder(content));
        if (messageId != null) {
            recorder.partialRefs.put(runId, new AssistantRunRecorder.PartialRef(messageId, "000000", 1L, 42L));
        }
    }

    @Test
    void 失联收割_锚转truncated_run条件终态_退回合_跟踪清理() {
        seedRun("run-1", "th-1", "已生成部分", 9001L);
        recorder.lastEventAt.put("run-1", NOW - STALL - 1);
        when(costLedger.isInFlight("th-1")).thenReturn(false);
        when(conversationService.runByRunId("run-1")).thenReturn(run("000000", 42L));
        when(conversationService.finalizeStalledRun("000000", "run-1")).thenReturn(List.of(run("000000", 42L)));

        recorder.flushAndReap(NOW);

        verify(conversationService).updatePartialMessage(eq("000000"), eq(9001L), eq("已生成部分"),
            isNull(), eq("truncated"));
        verify(conversationService).finalizeStalledRun("000000", "run-1");
        verify(dailyQuota).refundTurn("000000", 42L);
        assertThat(recorder.lastEventAt).doesNotContainKey("run-1");
        assertThat(recorder.partialRefs).doesNotContainKey("run-1");
        assertThat(recorder.buffers).doesNotContainKey("run-1");
    }

    /** 稳流① Q1 收口复核（2026-10-20 E2E 活体）：收割成功分支持久化前退款、计数一个不落——
     * 活体共享环境里收割计数缺失时，用本用例隔离定责（计数机制 vs 环境伪象） */
    @Test
    void 失联收割成功_终态计数入桶_按租户隔离() {
        seedRun("run-c", "th-c", "已生成部分", 9005L);
        recorder.lastEventAt.put("run-c", NOW - STALL - 1);
        when(costLedger.isInFlight("th-c")).thenReturn(false);
        when(conversationService.runByRunId("run-c")).thenReturn(run("000000", 42L));
        when(conversationService.finalizeStalledRun("000000", "run-c")).thenReturn(List.of(run("000000", 42L)));

        recorder.flushAndReap(NOW);

        assertThat(recorder.terminalOutcomeSnapshot("000000"))
            .containsEntry("INTERRUPTED|client-disconnect", 1L);
        // 多租户隔离：别的租户读不到 000000 的收割计数
        assertThat(recorder.terminalOutcomeSnapshot("999999")).isEmpty();
    }

    @Test
    void 模型调用在飞_停滞也不收割() {
        seedRun("run-2", "th-2", "生成中", null);
        recorder.lastEventAt.put("run-2", NOW - STALL - 1);
        when(costLedger.isInFlight("th-2")).thenReturn(true);

        recorder.flushAndReap(NOW);

        verify(conversationService, never()).finalizeStalledRun(anyString(), anyString());
        verify(dailyQuota, never()).refundTurn(anyString(), any());
        assertThat(recorder.lastEventAt).containsKey("run-2");
    }

    @Test
    void 收割db失败_保留原停滞时刻不回填_锚放回等重试() {
        seedRun("run-3", "th-3", "已生成部分", 9003L);
        long staleAt = NOW - STALL - 1;
        recorder.lastEventAt.put("run-3", staleAt);
        when(costLedger.isInFlight("th-3")).thenReturn(false);
        when(conversationService.runByRunId("run-3")).thenReturn(run("000000", 42L));
        when(conversationService.finalizeStalledRun("000000", "run-3")).thenThrow(new RuntimeException("db down"));

        recorder.flushAndReap(NOW);

        // ISSUE-002：旧值原样保留——回填伪造停滞时刻会让重试轮把终道刚接管的活 run 误收割
        assertThat(recorder.lastEventAt.get("run-3")).isEqualTo(staleAt);
        assertThat(recorder.partialRefs).containsKey("run-3");
        verify(dailyQuota, never()).refundTurn(anyString(), any());
    }

    @Test
    void 活run冲刷_过节流原地更新partial_内容无变不空写() {
        seedRun("run-4", "th-4", "部分答案", 9004L);
        recorder.lastPartialLen.put("run-4", 0);
        recorder.lastPartialFlushAt.put("run-4", NOW - 5_001L);

        recorder.flushAndReap(NOW);

        verify(conversationService).updatePartialMessage(eq("000000"), eq(9004L), eq("部分答案"),
            isNull(), eq("partial"));
        assertThat(recorder.lastPartialFlushAt.get("run-4")).isEqualTo(NOW);
        // 业务批7 Q12：lastPartialLen 语义=正文+活动联合指纹（dspro P2-4 位移组合）
        assertThat(recorder.lastPartialLen.get("run-4")).isEqualTo(AssistantRunRecorder.q12Fingerprint(4, 0));

        // 同 now 再 tick：内容无变（len 相同）→ 不空写
        recorder.flushAndReap(NOW);
        verify(conversationService, times(1)).updatePartialMessage(anyString(), any(), anyString(),
            any(), anyString());
    }

    @Test
    void 纯思考阶段_正文为空也建partial行_思考段随行落库() {
        // 业务批7 Q12 回归锁：旧门槛只认正文非空——纯思考阶段 partial 行永不建立，
        // 刷新即思考+答案全蒸发。现口径=思考/工具有内容即冲刷，stepsJson 含 thinkSegs。
        seedRun("run-6", "th-6", "", null);
        AssistantRunRecorder.ThinkSegBuf seg = new AssistantRunRecorder.ThinkSegBuf("m-1", 0, NOW);
        seg.text.append("先看全单位口径，再拆部门……");
        recorder.thinkBuffers.computeIfAbsent("th-6", k -> new java.util.ArrayList<>()).add(seg);
        org.mockito.Mockito.lenient().when(piiFilter.mask(anyString(), anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
        when(conversationService.runByRunId("run-6")).thenReturn(run("000000", 42L, "th-6"));
        org.dromara.assistant.domain.DcsAssistantConversation conv =
            new org.dromara.assistant.domain.DcsAssistantConversation();
        conv.setId(66L);
        when(conversationService.conversationByThread("000000", "th-6")).thenReturn(conv);

        recorder.flushAndReap(NOW);

        verify(conversationService).appendAssistantMessage(eq("000000"), eq(66L), eq(42L),
            eq(""), isNull(), eq("partial"), org.mockito.ArgumentMatchers.contains("thinkSegs"));
        assertThat(recorder.partialRefs).containsKey("run-6");
    }

    @Test
    void 无锚失联_不补插行_仅终态化run() {
        seedRun("run-5", "th-5", "残余文本", null);
        recorder.lastEventAt.put("run-5", NOW - STALL - 1);
        when(costLedger.isInFlight("th-5")).thenReturn(false);
        when(conversationService.runByRunId("run-5")).thenReturn(run("000000", 42L));
        when(conversationService.finalizeStalledRun("000000", "run-5")).thenReturn(List.of(run("000000", 42L)));

        recorder.flushAndReap(NOW);

        // dspro P0-1②：无锚=终道刚摘走（stop 权威行在途），补插必双行
        verify(conversationService).finalizeStalledRun("000000", "run-5");
        verify(conversationService, never()).updatePartialMessage(anyString(), any(), anyString(),
            any(), anyString());
        assertThat(recorder.lastEventAt).doesNotContainKey("run-5");
    }
}
