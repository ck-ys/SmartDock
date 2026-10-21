package com.smartdock.profile.event;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 访客键口径(批10 Q7): 物化表写入侧的 Java 实现必须与 SQL 侧
 * COALESCE(NULLIF(anonymous_id, ''), subject_id) 一字不差, 否则新访客判定漂移。
 */
@Tag("local")
class ProfileVisitorKeyTest {

    @Test
    void 匿名ID优先() {
        assertThat(ProfileEventServiceImpl.visitorKeyOf("anon-1", "user-9")).isEqualTo("anon-1");
    }

    @Test
    void 空白匿名回落主体() {
        assertThat(ProfileEventServiceImpl.visitorKeyOf("", "user-9")).isEqualTo("user-9");
        assertThat(ProfileEventServiceImpl.visitorKeyOf(null, "user-9")).isEqualTo("user-9");
        assertThat(ProfileEventServiceImpl.visitorKeyOf("   ", "user-9")).isEqualTo("user-9");
    }

    @Test
    void 两侧皆空返回空() {
        assertThat(ProfileEventServiceImpl.visitorKeyOf(null, null)).isNull();
        assertThat(ProfileEventServiceImpl.visitorKeyOf("", "")).isEmpty();
    }
}
