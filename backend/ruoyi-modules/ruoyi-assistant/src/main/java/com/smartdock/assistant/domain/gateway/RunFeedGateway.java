package com.smartdock.assistant.domain.gateway;

import io.agentscope.core.agui.event.AguiEvent;
import reactor.core.publisher.Flux;

import java.util.Optional;

/**
 * 「续看」取流的接口菜单（2026-11-17 结构批 1 ③）：service 只看本接口，
 * legacy 的 {@code AssistantRunMapper} / {@code AssistantRunStreamHub} 收口在 infrastructure 实现。
 *
 * <p>为什么建 gateway：流式汇是 legacy 内核的内部机制（保留窗 / replay 语义），
 * 属「会换实现 / 跨层收口」边界，符合根 AGENTS「gateway 三类边界」里的流式长连接一类。</p>
 */
public interface RunFeedGateway {

    /** 该 thread 最新 run 还在跑则给 runId，否则空（判定口径：status == RUNNING） */
    Optional<String> latestRunningRunId(String tenantId, Long userId, String threadId);

    /** 订阅该 run 的流；汇里没有（重启 / 逐出 / 不在本服务）返回空 */
    Optional<Flux<AguiEvent>> attach(String runId);

    /** 该 run 自创建起累计喂入帧数（attach 时判保留窗溢出） */
    long fedCount(String runId);

    /** 保留窗大小（帧数），溢出即头部有缺 */
    int ringSize();

    /** 溢出提示帧的类型标识 */
    String truncatedFrame();
}
