package org.dromara.assistant.persist;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import java.util.List;
import org.dromara.assistant.core.AssistantToolProgressConverter;
import org.dromara.assistant.core.DcsHeartbeatMiddleware;
import org.dromara.assistant.limiter.AssistantDailyQuota;
import org.dromara.assistant.security.AssistantPiiFilter;
import org.dromara.assistant.service.AssistantConversationService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ckys3 题1 三审席1 P1 红绿单测：tool_progress 进度帧不得喂养「业务活性」——
 * 它只证明节拍器活着（独立线程 2s 一跳），不证明工具/模型在动；若被算成业务帧，
 * 挂死工具会无限刷新 lastBusinessEventAt，10 分钟纯心跳收割永不触发（额度/资源占死）。
 */
@Tag("local")
@ExtendWith(MockitoExtension.class)
class AssistantRunRecorderLivenessExemptTest {

    @Mock
    private AssistantConversationService conversationService;
    @Mock
    private AssistantDailyQuota dailyQuota;
    @Mock
    private AssistantPendingInterruptCache pendingInterrupts;
    @Mock
    private AssistantPiiFilter piiFilter;
    @Mock
    private org.dromara.assistant.service.AssistantFollowupService followupService;
    @Mock
    private org.dromara.assistant.billing.AssistantCostLedger costLedger;

    @InjectMocks
    private AssistantRunRecorder recorder;

    @Test
    void exemptionListCoversHeartbeatAndProgressOnly() {
        assertTrue(AssistantRunRecorder.isLivenessExemptFrame(DcsHeartbeatMiddleware.HEARTBEAT_NAME),
            "心跳=连接级帧，不算业务");
        assertTrue(AssistantRunRecorder.isLivenessExemptFrame(AssistantToolProgressConverter.FRAME_NAME),
            "工具进度=节拍器帧，不算业务（三审席1 P1）");
        assertFalse(AssistantRunRecorder.isLivenessExemptFrame("cost_meta"), "计费帧=真业务");
        assertFalse(AssistantRunRecorder.isLivenessExemptFrame(null), "无名帧按业务算（fail-safe 侧）");
    }

    @Test
    void progressOnlyBatchDoesNotFeedBusinessLiveness() {
        AguiStreamContext ctx = new AguiStreamContext("t-lv", "r-lv", AguiAdapterConfig.builder().build());
        recorder.onEvents(ctx, List.of(new AguiEvent.Custom("t-lv", "r-lv",
            AssistantToolProgressConverter.FRAME_NAME, java.util.Map.of("text", "执行中·已用时 2 秒"))));

        // 两张活性表均为包级字段（为同包单测留的直断口），不走反射
        assertFalse(recorder.lastBusinessEventAt.containsKey("r-lv"),
            "纯进度帧批不得刷新业务活性时刻（刷新=挂死收割被击穿）");
        assertTrue(recorder.lastEventAt.containsKey("r-lv"),
            "连接级 lastEventAt 照常刷新（看门狗口径不变）");
    }
}
