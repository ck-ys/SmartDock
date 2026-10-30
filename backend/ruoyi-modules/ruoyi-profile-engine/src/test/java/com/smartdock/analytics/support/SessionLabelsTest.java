package com.smartdock.analytics.support;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题10 身份标签规则：登录取昵称、匿名给短标签，两条都不能把 null/空串兜成 "null"。
 */
@Tag("local")
class SessionLabelsTest {

    @Test
    void loggedInUserOnlyAcceptsNumericUserSubject() {
        assertThat(SessionLabels.loggedInUserId("user", "1")).isEqualTo(1L);
        assertThat(SessionLabels.loggedInUserId(" USER ", " 9100000001 ")).isEqualTo(9100000001L);
        assertThat(SessionLabels.loggedInUserId("visitor", "1")).isNull();
        assertThat(SessionLabels.loggedInUserId(null, "1")).isNull();
        assertThat(SessionLabels.loggedInUserId("user", "abc")).isNull();
        assertThat(SessionLabels.loggedInUserId("user", " ")).isNull();
        assertThat(SessionLabels.loggedInUserId("user", null)).isNull();
        // 超出 long 的数字串（伪雪花/脏数据）解析失败，按匿名处理而不是抛异常
        assertThat(SessionLabels.loggedInUserId("user", "123456789012345678901234")).isNull();
    }

    @Test
    void visitorLabelUsesAnonymousIdTailThenVisitorKey() {
        assertThat(SessionLabels.visitorLabel("anon-0003", "v-9")).isEqualTo("访客 #0003");
        assertThat(SessionLabels.visitorLabel(null, "abcdef1234")).isEqualTo("访客 #1234");
        assertThat(SessionLabels.visitorLabel("", "ab")).isEqualTo("访客 #ab");
        assertThat(SessionLabels.visitorLabel("ab", null)).isEqualTo("访客 #ab");
        assertThat(SessionLabels.visitorLabel("   ", "  ")).isEqualTo("访客 #----");
        assertThat(SessionLabels.visitorLabel(null, null)).isEqualTo("访客 #----");
    }
}
