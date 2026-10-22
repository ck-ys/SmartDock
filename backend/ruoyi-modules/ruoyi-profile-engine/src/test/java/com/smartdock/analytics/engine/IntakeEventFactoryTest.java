package com.smartdock.analytics.engine;

import com.smartdock.profile.event.ProfileEventBo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据接入页工厂单测（批Ⅰ·2026-10-22 拍板全按推荐）：纯函数直测，无 Spring。
 *
 * @author smartdock
 */
@Tag("local")
class IntakeEventFactoryTest {

    @Test
    void siteKeyFormatAndUniqueness() {
        Random random = new Random(42L);
        Set<String> keys = java.util.stream.IntStream.range(0, 1000)
            .mapToObj(i -> IntakeEventFactory.generateSiteKey(random))
            .collect(Collectors.toSet());
        assertThat(keys).hasSize(1000);
        for (String key : keys) {
            assertThat(key).startsWith("sd_").hasSize(35);
            assertThat(key.substring(3)).matches("[0-9a-f]{32}");
        }
    }

    @Test
    void simulatedEventsShape() {
        Random random = new Random(7L);
        Date now = new Date();
        List<ProfileEventBo> events = IntakeEventFactory.buildSimulatedEvents(5, "web", now, random);

        // 每个访客至少 1 条事件；总量 ≥ 访客数
        assertThat(events.size()).isGreaterThanOrEqualTo(5);
        // 全部带 sim- 前缀与 simulated 标记（可一键清理的依据）
        for (ProfileEventBo event : events) {
            assertThat(event.getEventUid()).startsWith(IntakeEventFactory.SIM_UID_PREFIX);
            assertThat(event.getProps()).containsEntry("simulated", true);
            assertThat(event.getSource()).isEqualTo("web");
            assertThat(event.getSubjectId()).startsWith("sim-visitor-");
            assertThat(event.getSessionId()).startsWith("sim-session-");
        }
        // eventUid 全局不重复
        Set<String> uids = events.stream().map(ProfileEventBo::getEventUid).collect(Collectors.toSet());
        assertThat(uids).hasSize(events.size());
        // 会话数 ≤ 访客数（一人一会话）
        Map<String, Integer> bySession = IntakeEventFactory.countBySession(events);
        assertThat(bySession.size()).isLessThanOrEqualTo(5).isGreaterThanOrEqualTo(1);
        // page_view 事件页面路径同时写 props 与 context（内置 Schema 9701 的 required 校验只看 props，
        // 会话重建/页面分析读 context.pageUrl|url|path——两处都必须有，缺一处就是静默拒收或落"未知页面"）
        events.stream().filter(e -> "page_view".equals(e.getEventName()))
            .forEach(e -> {
                assertThat(e.getProps()).containsKey("pageUrl");
                assertThat(e.getContext()).containsKey("pageUrl");
                assertThat(e.getContext()).doesNotContainKey("page");
            });
        // 事件时间一律落在 now 之前（实时看板按 occurred_time < now 过滤，不能有未来时间戳）
        for (ProfileEventBo event : events) {
            assertThat(event.getOccurredTime().getTime()).isLessThanOrEqualTo(now.getTime());
        }
    }

    @Test
    void simulatedEventsStayInSessionWindow() {
        Random random = new Random(99L);
        Date now = new Date(1_800_000_000_000L);
        List<ProfileEventBo> events = IntakeEventFactory.buildSimulatedEvents(20, "web", now, random);
        // 会话内步进数秒、绝不跨 30 分钟切分窗（同会话事件全部落在 now 往前 1 分钟内，且不含未来）
        for (ProfileEventBo event : events) {
            long delta = now.getTime() - event.getOccurredTime().getTime();
            assertThat(delta).isGreaterThanOrEqualTo(0L).isLessThan(60_000L);
        }
    }

    @Test
    void detectEventShape() {
        ProfileEventBo event = IntakeEventFactory.buildDetectEvent("web", "sim-detect-probe-1");
        assertThat(event.getEventName()).isEqualTo("intake_detect");
        assertThat(event.getEventUid()).isEqualTo("sim-detect-probe-1");
        assertThat(event.getEventUid()).startsWith(IntakeEventFactory.SIM_UID_PREFIX);
        assertThat(event.getProps()).containsEntry("detect", true);
        assertThat(event.getContext()).containsKey("pageUrl");
        assertThat(event.getSource()).isEqualTo("web");
    }

    @Test
    void visitorsClampedUpstreamIsHonored() {
        // 服务层夹紧 1..20，工厂对 1 与 20 均正常出数
        assertThat(IntakeEventFactory.buildSimulatedEvents(1, "web", new Date(), new Random(1L))).isNotEmpty();
        assertThat(IntakeEventFactory.buildSimulatedEvents(20, "web", new Date(), new Random(2L))).hasSizeGreaterThanOrEqualTo(20);
    }
}
