package com.smartdock.assistant.infrastructure;

import com.smartdock.assistant.domain.gateway.RunFeedGateway;
import io.agentscope.core.agui.event.AguiEvent;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.core.AssistantRunStreamHub;
import org.dromara.assistant.domain.DcsAssistantRun;
import org.dromara.assistant.mapper.AssistantRunMapper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

/**
 * {@link RunFeedGateway} 的唯一实现（2026-11-17 结构批 1 ③）。
 *
 * <p>本类是新根里<b>唯二</b>允许 import {@code AssistantRunMapper} / {@code AssistantRunStreamHub}
 * 的地方（另一个是 ToolCatalogGatewayImpl）；service 层从此不再摸 legacy 类型。
 * 判定口径与原 {@code ConversationResumeService} 逐行一致，只是搬了家。</p>
 */
@Component
@RequiredArgsConstructor
public class RunFeedGatewayImpl implements RunFeedGateway {

    private final AssistantRunMapper runMapper;
    private final AssistantRunStreamHub hub;

    @Override
    public Optional<String> latestRunningRunId(String tenantId, Long userId, String threadId) {
        List<DcsAssistantRun> latest = runMapper.selectByThread(tenantId, threadId, userId, 1);
        if (latest.isEmpty() || !"RUNNING".equals(latest.get(0).getStatus())) {
            return Optional.empty();
        }
        // runId 列 NOT NULL，但守一道：取不到按「不可续看」走，不炸 500（多审席 A P2-1）
        String runId = latest.get(0).getRunId();
        return runId == null ? Optional.empty() : Optional.of(runId);
    }

    @Override
    public Optional<Flux<AguiEvent>> attach(String runId) {
        return hub.attach(runId);
    }

    @Override
    public long fedCount(String runId) {
        return hub.fedCount(runId);
    }

    @Override
    public int ringSize() {
        return AssistantRunStreamHub.RING;
    }

    @Override
    public String truncatedFrame() {
        return AssistantRunStreamHub.TRUNCATED_FRAME;
    }
}
