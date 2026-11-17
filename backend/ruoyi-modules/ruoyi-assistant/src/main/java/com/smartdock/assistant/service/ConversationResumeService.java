package com.smartdock.assistant.service;

import com.smartdock.assistant.domain.gateway.RunFeedGateway;
import io.agentscope.core.agui.event.AguiEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;

/**
 * 「续看」准备段（2026-11-17 结构治理批 Q5：从 {@code AssistantConversationController.attach}
 * 下沉；批 1 ③ 起 legacy 依赖经 {@link RunFeedGateway} 收口）。
 * 只做「能不能续、拿哪条流」的判定与拼装，<b>不持有 emitter / flux 成员</b>；
 * SSE 的生命周期（onCompletion / onTimeout / 订阅与停看）仍留在 Controller。
 */
@Service
@RequiredArgsConstructor
public class ConversationResumeService {

    private final RunFeedGateway runFeed;

    /** 续看准备结果：失败回业务话术（200 包 R.fail，前端据此回退轮询），成功交出待订阅的流 */
    public sealed interface ResumeResult {
        record Failed(String message) implements ResumeResult {
        }

        record Ready(String runId, Flux<AguiEvent> flux) implements ResumeResult {
        }
    }

    /**
     * 判定该 thread 最新 run 是否仍在跑、汇里有没有它的流，并补「保留窗溢出」提示帧。
     *
     * <p>口径与下沉前逐行一致：不在跑 / 汇已逐出都返回失败话术，由前端回退到
     * 既有「轮询等终态再重放历史」路径。</p>
     */
    public ResumeResult prepare(String tenantId, Long userId, String threadId) {
        Optional<String> running = runFeed.latestRunningRunId(tenantId, userId, threadId);
        if (running.isEmpty()) {
            return new ResumeResult.Failed("本轮已结束，刷新消息列表即可看到全文");
        }
        String runId = running.get();
        Optional<Flux<AguiEvent>> fluxOpt = runFeed.attach(runId);
        if (fluxOpt.isEmpty()) {
            return new ResumeResult.Failed(
                "本轮不可续看（可能不在当前服务上、服务重启过或已超保留窗），刷新页面后重试");
        }
        Flux<AguiEvent> flux = fluxOpt.get();
        // 保留窗溢出（超长单轮）：首帧补提示，头部内容稍后从历史消息看全文。
        // 与原实现同口径：这一段（取流之后的拼装）出任何异常都回 200 + 业务错，不抛 500。
        try {
            if (runFeed.fedCount(runId) > runFeed.ringSize()) {
                flux = flux.startWith(new AguiEvent.Custom(
                    threadId, runId,
                    runFeed.truncatedFrame(),
                    Map.of("hint", "本回合内容较长，续看从中间开始；完整回答稍后在历史消息里")));
            }
            return new ResumeResult.Ready(runId, flux);
        } catch (Exception e) {
            return new ResumeResult.Failed("续看通道建立失败：" + e.getMessage());
        }
    }
}
