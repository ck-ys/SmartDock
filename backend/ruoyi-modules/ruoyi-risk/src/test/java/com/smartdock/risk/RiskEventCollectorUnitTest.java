package com.smartdock.risk;

import com.smartdock.risk.domain.RiskEvent;
import com.smartdock.risk.domain.RiskRule;
import com.smartdock.risk.domain.bo.RiskEventCommand;
import com.smartdock.risk.mapper.RiskEventMapper;
import com.smartdock.risk.mapper.RiskRuleMapper;
import com.smartdock.risk.mapper.RiskWhitelistMapper;
import com.smartdock.risk.service.RiskEventCollector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class RiskEventCollectorUnitTest {

    @Test
    void recordsExplicitTenantAndIdempotencyKey() {
        RiskEventMapper eventMapper = mock(RiskEventMapper.class);
        RiskRuleMapper ruleMapper = mock(RiskRuleMapper.class);
        RiskWhitelistMapper whitelistMapper = mock(RiskWhitelistMapper.class);
        RiskRule rule = new RiskRule();
        rule.setWeight(2);
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        when(whitelistMapper.selectCount(any())).thenReturn(0L);
        when(eventMapper.selectCount(any())).thenReturn(0L);

        RiskEventCollector collector = new RiskEventCollector(eventMapper, ruleMapper, whitelistMapper, mock(com.smartdock.risk.support.RiskBehaviorSink.class));
        Date occurredTime = new Date();

        collector.record(new RiskEventCommand(
            "200001",
            "SENSITIVE_WORD",
            "CHAT",
            7L,
            "127.0.0.1",
            "JUnit",
            99L,
            "hit",
            "chat:99:client-1",
            occurredTime,
            null
        ));

        ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("200001");
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("chat:99:client-1");
        assertThat(captor.getValue().getOccurredTime()).isEqualTo(occurredTime);
    }

    @Test
    void normalizesBlankIdempotencyKeyToNull() {
        RiskEventMapper eventMapper = mock(RiskEventMapper.class);
        RiskRuleMapper ruleMapper = mock(RiskRuleMapper.class);
        RiskWhitelistMapper whitelistMapper = mock(RiskWhitelistMapper.class);
        RiskRule rule = new RiskRule();
        rule.setWeight(2);
        when(ruleMapper.selectOne(any())).thenReturn(rule);
        when(whitelistMapper.selectCount(any())).thenReturn(0L);
        RiskEventCollector collector = new RiskEventCollector(eventMapper, ruleMapper, whitelistMapper, mock(com.smartdock.risk.support.RiskBehaviorSink.class));

        collector.record(new RiskEventCommand(
            "200001",
            "SENSITIVE_WORD",
            "CHAT",
            7L,
            "127.0.0.1",
            "JUnit",
            99L,
            "hit",
            " ",
            new Date(),
            null
        ));

        ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isNull();
    }

    @Test
    void rejectsMissingTenantBeforeAsyncWork() {
        assertThatThrownBy(() -> new RiskEventCommand(
            " ",
            "SENSITIVE_WORD",
            "CHAT",
            7L,
            "127.0.0.1",
            "JUnit",
            99L,
            "hit",
            "chat:99:client-1",
            new Date(),
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tenantId");
    }

    @Test
    void rejectsMissingEventTypeBeforeAsyncWork() {
        assertThatThrownBy(() -> new RiskEventCommand(
            "200001",
            " ",
            "CHAT",
            7L,
            "127.0.0.1",
            "JUnit",
            99L,
            "hit",
            "chat:99:client-1",
            new Date(),
            null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("eventType");
    }

    @Test
    void ignoresDuplicateIdempotencyKey() {
        RiskEventMapper eventMapper = mock(RiskEventMapper.class);
        RiskRuleMapper ruleMapper = mock(RiskRuleMapper.class);
        RiskWhitelistMapper whitelistMapper = mock(RiskWhitelistMapper.class);
        when(whitelistMapper.selectCount(any())).thenReturn(0L);
        when(eventMapper.selectCount(any())).thenReturn(1L);
        RiskEventCollector collector = new RiskEventCollector(eventMapper, ruleMapper, whitelistMapper, mock(com.smartdock.risk.support.RiskBehaviorSink.class));

        collector.record(new RiskEventCommand(
            "200001",
            "SENSITIVE_WORD",
            "CHAT",
            7L,
            "127.0.0.1",
            "JUnit",
            99L,
            "hit",
            "chat:99:client-1",
            new Date(),
            null
        ));

        verify(eventMapper, never()).insert(any(RiskEvent.class));
        verify(ruleMapper, never()).selectOne(any());
    }
}
