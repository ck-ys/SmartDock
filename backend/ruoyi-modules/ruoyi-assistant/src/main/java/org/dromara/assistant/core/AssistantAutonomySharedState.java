package org.dromara.assistant.core;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * 自主权 once 批准的跨实例存储（多实例批 1）。
 *
 * <p>只搬 {@code APPROVED / APPROVED_GEN / RULE_INSTALL_PENDING}；档位
 * {@code autoLevel} 本来就是每请求上行，搬 Redis 没有收益。local 模式不绑定，
 * 保持原来的本机静态 Map 行为。</p>
 *
 * <p>键：{@code global:smartdock:asst:autonomy:approved:{thread}}（集合）、
 * {@code ...:autonomy:gen:{thread}}（代次）、{@code ...:autonomy:rule:{thread}}（规则装回标记），
 * 统一 24 小时过期。{@code global:} 用于绕开多租户前缀，与计划/租约键同源。</p>
 */
@Slf4j
@Component
public class AssistantAutonomySharedState {

    private static final Duration TTL = Duration.ofHours(24);

    private final AssistantSharedState shared;

    public AssistantAutonomySharedState(AssistantSharedState shared) {
        this.shared = shared;
    }

    @PostConstruct
    void bind() {
        if (!shared.redisEnabled()) {
            return;
        }
        AssistantAutonomyHolder.bindShared(new Backend());
        log.info("[autonomy-shared] 已绑定共享后端（模式={}）", shared.mode());
    }

    @PreDestroy
    void unbind() {
        AssistantAutonomyHolder.bindShared(null);
    }

    private static String approvedKey(String threadId) {
        return AssistantSharedState.key("autonomy:approved:" + threadId);
    }

    private static String genKey(String threadId) {
        return AssistantSharedState.key("autonomy:gen:" + threadId);
    }

    private static String ruleKey(String threadId) {
        return AssistantSharedState.key("autonomy:rule:" + threadId);
    }

    private final class Backend implements AssistantAutonomyHolder.SharedBackend {

        @Override
        public boolean redisReadPreferred() {
            return shared.redisReadPreferred();
        }

        @Override
        public Set<String> approved(String threadId) {
            return shared.setMembers(approvedKey(threadId));
        }

        @Override
        public long approvedGen(String threadId) {
            return shared.getLong(genKey(threadId), 0L);
        }

        @Override
        public void approve(String threadId, String toolName) {
            shared.addToSetAndIncrement(approvedKey(threadId), toolName, genKey(threadId), TTL);
        }

        @Override
        public void markRuleInstallPending(String threadId) {
            shared.setFlag(ruleKey(threadId), true, TTL);
        }

        @Override
        public boolean consumeRuleInstallPending(String threadId) {
            if (!shared.getFlag(ruleKey(threadId))) {
                return false;
            }
            shared.delete(ruleKey(threadId));
            return true;
        }
    }
}
