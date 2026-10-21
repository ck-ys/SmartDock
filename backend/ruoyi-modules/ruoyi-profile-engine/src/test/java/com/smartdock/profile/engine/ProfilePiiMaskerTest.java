package com.smartdock.profile.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII 打码纯函数测试(批11 Q1)。
 */
@Tag("local")
class ProfilePiiMaskerTest {

    @Test
    void mobileKeepsHeadAndTail() {
        assertThat(ProfilePiiMasker.mask("MOBILE", "13812345678")).isEqualTo("138****5678");
        assertThat(ProfilePiiMasker.mask("MOBILE", "not-a-phone")).isEqualTo("n***e"); // 非手机号回退 EDGE
    }

    @Test
    void emailKeepsFirstCharAndDomain() {
        assertThat(ProfilePiiMasker.mask("EMAIL", "alice@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void ipMasksLastTwoSegments() {
        assertThat(ProfilePiiMasker.mask("IP", "192.168.3.131")).isEqualTo("192.168.*.*");
        assertThat(ProfilePiiMasker.mask("IP", "not-ip")).isEqualTo("***");
    }

    @Test
    void termAndFullFullyMasked() {
        assertThat(ProfilePiiMasker.mask("TERM", "买了什么")).isEqualTo("***");
        assertThat(ProfilePiiMasker.mask("FULL", "110101199001011234")).isEqualTo("***");
        assertThat(ProfilePiiMasker.mask(null, "anything")).isEqualTo("***");
    }

    @Test
    void edgeKeepsFirstAndLastChar() {
        assertThat(ProfilePiiMasker.mask("EDGE", "北京市朝阳区")).isEqualTo("北***区");
        assertThat(ProfilePiiMasker.mask("EDGE", "ab")).isEqualTo("***");
    }

    @Test
    void blankValuesPassThrough() {
        assertThat(ProfilePiiMasker.mask("MOBILE", null)).isNull();
        assertThat(ProfilePiiMasker.mask("MOBILE", " ")).isEqualTo(" ");
    }

    @Test
    void maskMapMatchesKeyIgnoringCaseAndSeparators() {
        Map<String, Object> out = ProfilePiiMasker.maskMap(
            Map.of("Phone", "13812345678", "search-term", "口红", "page", "/home", "count", 3),
            Map.of("phone", "MOBILE", "search_term", "TERM"));
        assertThat(out.get("Phone")).isEqualTo("138****5678");
        assertThat(out.get("search-term")).isEqualTo("***");
        assertThat(out.get("page")).isEqualTo("/home");
        assertThat(out.get("count")).isEqualTo(3);
    }
}
