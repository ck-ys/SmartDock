package com.smartdock.analytics;

import com.smartdock.analytics.domain.AnalyticsSession;
import com.smartdock.analytics.engine.AnalyticsSessionizer;
import com.smartdock.analytics.query.AnalyticsEventRow;
import com.smartdock.profile.monitor.SelfMonitorEvents;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E 片统一事件流口径钉: 内部操作流(登录/核心操作, 无页面事件)进会话重建后,
 * 单页离开与白名单新成员(data.audited)的语义不得回退。
 *
 * @author smartdock
 */
@Tag("local")
class AnalyticsAdoptionMetricsTest {

    private AnalyticsEventRow op(long id, String name, String user, long minutesAfterBase) {
        AnalyticsEventRow row = new AnalyticsEventRow();
        row.setEventId(id);
        row.setEventName(name);
        row.setSubjectType("user");
        row.setSubjectId(user);
        row.setVisitorKey(user);
        row.setOccurredTime(new Date(1_700_000_000_000L + minutesAfterBase * 60_000L));
        row.setProps("{}");
        row.setContext("{}");
        return row;
    }

    @Test
    void loginOnlyInternalSessionIsSingleActionLeave() {
        // 登录即走: 整段只有一条有意义操作 = 单页离开
        List<AnalyticsSession> sessions = AnalyticsSessionizer.build("000000", "dcs", List.of(
            op(1, "user.login", "u-1", 0)
        ), Map.of());
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getIsBounce()).isEqualTo("1");
        assertThat(sessions.get(0).getVisitorId()).isEqualTo("u-1");
    }

    @Test
    void loginPlusCoreOperationIsNotLeave() {
        // 登录 + 做了一件事 = 正常使用段
        List<AnalyticsSession> sessions = AnalyticsSessionizer.build("000000", "dcs", List.of(
            op(1, "user.login", "u-1", 0),
            op(2, "data.submitted", "u-1", 5)
        ), Map.of());
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getIsBounce()).isEqualTo("0");
        assertThat(sessions.get(0).getEventCount()).isEqualTo(2);
    }

    @Test
    void auditorActionEventIsSelfMonitorCoreOperation() {
        // E 片审核岗归属修复: 审核人侧事件必须进白名单, 否则采用度/两步漏斗取数静默漏人
        assertThat(SelfMonitorEvents.CORE_OPERATIONS).contains(SelfMonitorEvents.DATA_AUDITED);
        assertThat(SelfMonitorEvents.DATA_AUDITED).isEqualTo("data.audited");
        assertThat(SelfMonitorEvents.isSelfMonitor("data.audited")).isTrue();
    }
}
