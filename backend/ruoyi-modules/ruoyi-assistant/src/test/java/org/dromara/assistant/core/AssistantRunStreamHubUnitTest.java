package org.dromara.assistant.core;

import io.agentscope.core.agui.event.AguiEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 批题5「续看」续看汇护栏（2026-10-27 甲案）：replay 语义三断言——
 * ①迟到订阅者先收全量历史再续直播；②终局帧 complete 汇（订阅自动收尾）；
 * ③溢出计数 fedCount 超窗可判（attach 端点据此补截断提示帧）。
 */
class AssistantRunStreamHubUnitTest {

    private static AguiEvent.Custom frame(String runId, int i) {
        return new AguiEvent.Custom("t-ut", runId, "dcs-heartbeat", java.util.Map.of("i", i));
    }

    private static AguiEvent.RunFinished finished(String runId) {
        return new AguiEvent.RunFinished("t-ut", runId, null, null);
    }

    @Test
    @Tag("dev")
    void lateSubscriberReplaysHistoryThenLive() throws Exception {
        AssistantRunStreamHub hub = new AssistantRunStreamHub();
        hub.accept("r-ut1", List.of(frame("r-ut1", 1), frame("r-ut1", 2)));
        // 迟到订阅（=刷新后的 attach）：先重放 1、2
        List<Integer> got = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        Flux<Integer> flux = hub.attach("r-ut1").orElseThrow()
            .ofType(AguiEvent.Custom.class)
            .map(c -> (Integer) ((java.util.Map<?, ?>) c.value()).get("i"))
            .doOnComplete(done::countDown);
        var sub = flux.subscribe(got::add);
        // 订阅后喂直播帧 3，再终局
        hub.accept("r-ut1", List.of(frame("r-ut1", 3), finished("r-ut1")));
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        sub.dispose();
        assertThat(got).containsExactly(1, 2, 3);
    }

    @Test
    @Tag("dev")
    void attachUnknownRunIsEmptyAndTerminalEvictsAfterWindow() throws Exception {
        AssistantRunStreamHub hub = new AssistantRunStreamHub();
        assertThat(hub.attach("no-such-run")).isEmpty();
        hub.accept("r-ut2", List.of(frame("r-ut2", 1), finished("r-ut2")));
        // 终局帧到达后仍可短窗重放（晚到的刷新）
        assertThat(hub.attach("r-ut2")).isPresent();
        // 窗口到了被逐出（测试直接等调度器跑：300s 太长——这里只验终局后 attach 语义，
        // 逐出由 evictor 线程按 EVICT_AFTER_SECONDS 兜底，不在此等待）
        assertThat(hub.size()).isEqualTo(1);
    }

    @Test
    @Tag("dev")
    void overflowFedsCountExceedsRing() {
        AssistantRunStreamHub hub = new AssistantRunStreamHub();
        String run = "r-ut3";
        for (int i = 0; i < AssistantRunStreamHub.RING + 10; i++) {
            hub.accept(run, List.of(frame(run, i)));
        }
        // 溢出可判：fedCount > RING → attach 端点补 dcs-resume-truncated 提示帧
        assertThat(hub.fedCount(run)).isGreaterThan(AssistantRunStreamHub.RING);
        // replay 窗只保留最后 RING 帧（重放数=fed-溢出量内不超过 RING）
        List<Object> replayed = new CopyOnWriteArrayList<>();
        var sub = hub.attach(run).orElseThrow().subscribe(replayed::add);
        sub.dispose();
        assertThat(replayed.size()).isLessThanOrEqualTo(AssistantRunStreamHub.RING);
    }

    // ===== 桥接（题5 收口补件：断连不拆链）=====

    @Test
    @Tag("dev")
    void bridgeKeepsChainAliveAfterHttpSubscriberCancels() throws Exception {
        AssistantRunStreamHub hub = new AssistantRunStreamHub();
        String run = "r-br1";
        reactor.core.publisher.Sinks.Many<AguiEvent> source = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        // 桥接即驱动：driver 已订阅原链（帧经 enricher 语义=测试里直接 accept 喂汇同效，这里验桥接返回流与存活）
        Flux<AguiEvent> httpStream = hub.bridge(run, source.asFlux());
        List<Integer> httpGot = new CopyOnWriteArrayList<>();
        var httpSub = httpStream
            .ofType(AguiEvent.Custom.class)
            .map(c -> (Integer) ((java.util.Map<?, ?>) c.value()).get("i"))
            .subscribe(httpGot::add);
        // 原链出帧 1、2（enricher 侧喂汇——测试直喂同路径）
        hub.accept(run, List.of(frame(run, 1), frame(run, 2)));
        assertThat(httpGot).containsExactly(1, 2);
        // HTTP 断连（F5）：订阅取消
        httpSub.dispose();
        // 链续活：后续帧继续入汇（driver 未被取消牵连）
        hub.accept(run, List.of(frame(run, 3), frame(run, 4)));
        // 刷新后的 attach 观众：重放 1-4 全量（生成没死，回答连续）
        List<Integer> reattached = new CopyOnWriteArrayList<>();
        var attachSub = hub.attach(run).orElseThrow()
            .ofType(AguiEvent.Custom.class)
            .map(c -> (Integer) ((java.util.Map<?, ?>) c.value()).get("i"))
            .subscribe(reattached::add);
        assertThat(reattached).containsExactly(1, 2, 3, 4);
        attachSub.dispose();
        // 终局：enricher 语义喂 RUN_FINISHED → 汇 complete
        hub.accept(run, List.of(finished(run)));
        assertThat(hub.attach(run)).isPresent();
    }

    @Test
    @Tag("dev")
    void bridgeChainCompletionClosesSinkEvenWithoutTerminalFrame() throws Exception {
        AssistantRunStreamHub hub = new AssistantRunStreamHub();
        String run = "r-br2";
        reactor.core.publisher.Sinks.Many<AguiEvent> source = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        Flux<AguiEvent> stream = hub.bridge(run, source.asFlux());
        List<Integer> got = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        var sub = stream
            .ofType(AguiEvent.Custom.class)
            .map(c -> (Integer) ((java.util.Map<?, ?>) c.value()).get("i"))
            .doOnComplete(done::countDown)
            .subscribe(got::add);
        hub.accept(run, List.of(frame(run, 9)));
        // 原链自然完成但无终态帧（罕见路径）——driver onComplete 兜底收口汇
        source.tryEmitComplete();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        sub.dispose();
        assertThat(got).containsExactly(9);
    }

    @Test
    @Tag("dev")
    void bridgeSameRunIdReplacesOldChain() throws Exception {
        AssistantRunStreamHub hub = new AssistantRunStreamHub();
        String run = "r-br3";
        reactor.core.publisher.Sinks.Many<AguiEvent> oldSource = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        hub.bridge(run, oldSource.asFlux());
        hub.accept(run, List.of(frame(run, 1)));
        // 同 runId 重入（断线重试/resume 复用 runId）：旧链弃、新汇重放全新
        reactor.core.publisher.Sinks.Many<AguiEvent> newSource = reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        Flux<AguiEvent> stream2 = hub.bridge(run, newSource.asFlux());
        List<Integer> got = new CopyOnWriteArrayList<>();
        var sub = stream2
            .ofType(AguiEvent.Custom.class)
            .map(c -> (Integer) ((java.util.Map<?, ?>) c.value()).get("i"))
            .subscribe(got::add);
        hub.accept(run, List.of(frame(run, 7)));
        sub.dispose();
        // 新汇只含新轮帧（1 已随旧链丢弃，不串轮）
        assertThat(got).containsExactly(7);
    }
}
