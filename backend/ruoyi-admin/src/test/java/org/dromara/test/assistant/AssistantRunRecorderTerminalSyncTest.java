package org.dromara.test.assistant;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import com.smartdock.assistant.service.AssistantCostLedger;
import com.smartdock.assistant.engine.persist.AssistantPendingInterruptCache;
import com.smartdock.assistant.engine.persist.AssistantRunRecorder;
import com.smartdock.assistant.service.RunLedgerService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-11-18 上游队列验收第 3 项：RUN_FINISHED 的终态落库必须在 onEvents 返回前完成。
 *
 * <p>判据不是“最终会调用”，而是“调用期间 onEvents 还没返回”：如果仍走异步池，
 * 事件线程会先返回，测试里的 eventThread 会死掉；改成同线程同步后，onEvents 必须
 * 一直等到 finishRun 返回。测试用两个 latch 卡住写库，最多等 1 秒观察事件线程是否
 * 仍存活；生产逻辑里没有 sleep/轮询。</p>
 */
@Tag("local")
class AssistantRunRecorderTerminalSyncTest {

    @Test
    void terminalWriteCompletesBeforeOnEventsReturns() throws Exception {
        RunLedgerService service = mock(RunLedgerService.class);
        when(service.runByRunId("run-sync")).thenReturn(null);

        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        doAnswer(invocation -> {
            writeStarted.countDown();
            assertTrue(releaseWrite.await(5, TimeUnit.SECONDS), "测试释放终态写超时");
            return 1;
        }).when(service).finishRun("run-sync", "COMPLETED", "normal");

        AssistantRunRecorder recorder = new AssistantRunRecorder(
            service, null, new AssistantPendingInterruptCache(), null, null,
            new AssistantCostLedger());
        AguiStreamContext context =
            new AguiStreamContext("th-sync", "run-sync", AguiAdapterConfig.builder().build());
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread eventThread = new Thread(() -> {
            try {
                recorder.onEvents(context, List.of(new AguiEvent.RunFinished(
                    "th-sync", "run-sync", null, new AguiEvent.RunFinishedSuccessOutcome())));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "terminal-sync-test");
        eventThread.start();

        assertTrue(writeStarted.await(3, TimeUnit.SECONDS), "终态写应开始");
        // 给异步池留出抢跑时间：修前事件线程会在这里之前就结束；修后它必须仍被卡在写库。
        long deadline = System.currentTimeMillis() + 1000;
        while (eventThread.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(eventThread.isAlive(), "onEvents 必须仍然停在终态写里（同步），而不是先返回");

        releaseWrite.countDown();
        eventThread.join(5000);
        assertFalse(eventThread.isAlive(), "释放后 onEvents 应结束");
        assertNull(failure.get(), "onEvents 不应抛异常");
        verify(service, times(1)).finishRun("run-sync", "COMPLETED", "normal");
    }
}
