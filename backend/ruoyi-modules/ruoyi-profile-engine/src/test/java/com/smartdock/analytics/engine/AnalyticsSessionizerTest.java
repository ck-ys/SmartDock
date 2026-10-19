package com.smartdock.analytics.engine;

import com.smartdock.analytics.domain.AnalyticsSession;
import com.smartdock.analytics.query.AnalyticsEventRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话重建是纯函数，直接测分组/合并/跳出/渠道回退。
 */
@Tag("local")
class AnalyticsSessionizerTest {

    private static final long MINUTE = 60_000L;

    private AnalyticsEventRow event(long id, String name, String visitor, String sessionId,
                                    long minutesAfterBase, String contextJson) {
        AnalyticsEventRow row = new AnalyticsEventRow();
        row.setEventId(id);
        row.setEventName(name);
        row.setAnonymousId(visitor);
        row.setVisitorKey(visitor);
        row.setSessionId(sessionId);
        row.setOccurredTime(new Date(1_700_000_000_000L + minutesAfterBase * MINUTE));
        row.setProps("{}");
        row.setContext(contextJson);
        return row;
    }

    @Test
    void explicitSessionIdGroupsEventsTogether() {
        List<AnalyticsSession> sessions = AnalyticsSessionizer.build("000000", "web", List.of(
            event(1, "page_view", "v1", "s1", 0, "{\"pageUrl\":\"/home\",\"referrer\":\"https://www.baidu.com/\"}"),
            event(2, "page_view", "v1", "s1", 2, "{\"pageUrl\":\"/pricing\"}"),
            event(3, "signup", "v1", "s1", 4, "{\"pageUrl\":\"/signup\"}")
        ), Map.of());

        assertThat(sessions).hasSize(1);
        AnalyticsSession session = sessions.get(0);
        assertThat(session.getSessionKey()).isEqualTo("s:s1");
        assertThat(session.getPageViews()).isEqualTo(2);
        assertThat(session.getEventCount()).isEqualTo(3);
        assertThat(session.getEntryPage()).isEqualTo("/home");
        assertThat(session.getExitPage()).isEqualTo("/signup");
        assertThat(session.getSourceChannel()).isEqualTo("baidu");
        assertThat(session.getDurationSeconds()).isEqualTo(240);
        assertThat(session.getIsBounce()).isEqualTo("0");
    }

    @Test
    void implicitSessionSplitsAfterThirtyMinuteGap() {
        List<AnalyticsSession> sessions = AnalyticsSessionizer.build("000000", "web", List.of(
            event(1, "page_view", "v1", null, 0, "{\"pageUrl\":\"/home\"}"),
            event(2, "page_view", "v1", null, 10, "{\"pageUrl\":\"/docs\"}"),
            event(3, "page_view", "v1", null, 45, "{\"pageUrl\":\"/pricing\"}")
        ), Map.of());

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getPageViews()).isEqualTo(2);
        assertThat(sessions.get(1).getPageViews()).isEqualTo(1);
        assertThat(sessions.get(1).getSourceChannel()).isEqualTo("direct");
        assertThat(sessions.get(1).getIsBounce()).isEqualTo("1");
    }

    @Test
    void pageLeaveDoesNotBreakBounceDetection() {
        List<AnalyticsSession> sessions = AnalyticsSessionizer.build("000000", "web", List.of(
            event(1, "page_view", "v1", "s1", 0, "{\"pageUrl\":\"/home\"}"),
            event(2, "page_leave", "v1", "s1", 1, "{\"pageUrl\":\"/home\"}")
        ), Map.of());

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getPageViews()).isEqualTo(1);
        assertThat(sessions.get(0).getEventCount()).isEqualTo(2);
        assertThat(sessions.get(0).getIsBounce()).isEqualTo("1");
    }

    @Test
    void searchMakesSinglePageSessionNonBounce() {
        List<AnalyticsSession> sessions = AnalyticsSessionizer.build("000000", "web", List.of(
            event(1, "page_view", "v1", "s1", 0, "{\"pageUrl\":\"/home\"}"),
            event(2, "search", "v1", "s1", 1, "{\"pageUrl\":\"/search\",\"searchTerm\":\"crm\"}")
        ), Map.of());

        assertThat(sessions.get(0).getPageViews()).isEqualTo(1);
        assertThat(sessions.get(0).getIsBounce()).isEqualTo("0");
    }

    @Test
    void onlyFirstSessionOfVisitorIsNew() {
        Date base = new Date(1_700_000_000_000L);
        Map<String, Date> firstSeen = new HashMap<>();
        firstSeen.put("v1", base);

        List<AnalyticsSession> sessions = AnalyticsSessionizer.build("000000", "web", List.of(
            event(1, "page_view", "v1", "s1", 0, "{\"pageUrl\":\"/home\"}"),
            event(2, "page_view", "v1", "s2", 60, "{\"pageUrl\":\"/docs\"}")
        ), firstSeen);

        assertThat(sessions).hasSize(2);
        assertThat(sessions.get(0).getIsNewVisitor()).isEqualTo("1");
        assertThat(sessions.get(1).getIsNewVisitor()).isEqualTo("0");
    }

    @Test
    void singlePageViewSessionIsBounceAndNewVisitorUsesFirstSeen() {
        Date base = new Date(1_700_000_000_000L);
        Map<String, Date> firstSeen = new HashMap<>();
        firstSeen.put("v1", base);

        List<AnalyticsSession> sessions = AnalyticsSessionizer.build("000000", "web", List.of(
            event(1, "page_view", "v1", "s1", 0, "{\"pageUrl\":\"/home\"}")
        ), firstSeen);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getIsNewVisitor()).isEqualTo("1");
        assertThat(sessions.get(0).getIsBounce()).isEqualTo("1");
    }

    @Test
    void utmSourceBeatsReferrerHost() {
        List<AnalyticsSession> sessions = AnalyticsSessionizer.build("000000", "web", List.of(
            event(1, "page_view", "v1", "s1", 0,
                "{\"pageUrl\":\"/home\",\"referrer\":\"https://www.google.com/\",\"utm\":{\"source\":\"wechat\"}}")
        ), Map.of());

        assertThat(sessions.get(0).getSourceChannel()).isEqualTo("wechat");
    }

    @Test
    void canonicalUserSubjectUpgradesAnonymousVisitor() {
        AnalyticsEventRow anonymous = event(1, "page_view", "anon-1", "s1", 0, "{\"pageUrl\":\"/home\"}");
        AnalyticsEventRow identified = event(2, "page_view", "anon-1", "s1", 1, "{\"pageUrl\":\"/docs\"}");
        identified.setSubjectType("user");
        identified.setSubjectId("u-1001");

        List<AnalyticsSession> sessions = AnalyticsSessionizer.build("000000", "web",
            List.of(anonymous, identified), Map.of());

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getVisitorId()).isEqualTo("u-1001");
        assertThat(sessions.get(0).getSubjectType()).isEqualTo("user");
        assertThat(sessions.get(0).getSubjectId()).isEqualTo("u-1001");
    }
}
