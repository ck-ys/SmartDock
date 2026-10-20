package com.smartdock.analytics.quality;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 站点域名白名单匹配纯函数测试（批7 拍板 Q5-A：缺失放行、带来源严格匹配、支持通配）。
 */
@Tag("local")
class OriginDomainMatcherTest {

    @Test
    void blankAllowedDomainsMeansNoRestriction() {
        assertThat(OriginDomainMatcher.matches(null, "https://evil.example.com")).isTrue();
        assertThat(OriginDomainMatcher.matches("  ", "https://evil.example.com")).isTrue();
    }

    @Test
    void missingOriginPassesThrough() {
        // 同源请求与 curl 联调常不带 Origin, 缺失放行不误杀
        assertThat(OriginDomainMatcher.matches("localhost,127.0.0.1", null)).isTrue();
        assertThat(OriginDomainMatcher.matches("localhost,127.0.0.1", "")).isTrue();
        assertThat(OriginDomainMatcher.matches("localhost,127.0.0.1", "null")).isTrue();
    }

    @Test
    void exactHostMatchIgnoresSchemeAndPort() {
        assertThat(OriginDomainMatcher.matches("localhost,127.0.0.1", "http://localhost:5188")).isTrue();
        assertThat(OriginDomainMatcher.matches("localhost,127.0.0.1", "https://127.0.0.1/index.html")).isTrue();
        assertThat(OriginDomainMatcher.matches("localhost", "http://localhost:5188")).isTrue();
    }

    @Test
    void mismatchedHostIsRejected() {
        assertThat(OriginDomainMatcher.matches("localhost,127.0.0.1", "https://evil.example.com")).isFalse();
        assertThat(OriginDomainMatcher.matches("localhost", "https://notlocalhost.io")).isFalse();
    }

    @Test
    void subdomainNotAllowedByExactRule() {
        // 精确规则不含子域: a.localhost 不等于 localhost
        assertThat(OriginDomainMatcher.matches("example.com", "https://a.example.com")).isFalse();
        assertThat(OriginDomainMatcher.matches("example.com", "https://example.com")).isTrue();
    }

    @Test
    void wildcardCoversSubdomainsButNotBareDomain() {
        assertThat(OriginDomainMatcher.matches("*.example.com", "https://shop.example.com")).isTrue();
        assertThat(OriginDomainMatcher.matches("*.example.com", "https://a.b.example.com")).isTrue();
        assertThat(OriginDomainMatcher.matches("*.example.com", "https://example.com")).isFalse();
        // 通配后缀不可被伪域名绕过: *.example.com 不放行 fake-example.com
        assertThat(OriginDomainMatcher.matches("*.example.com", "https://fake-example.com")).isFalse();
    }

    @Test
    void refererUrlFormIsAlsoAccepted() {
        assertThat(OriginDomainMatcher.matches("docs.smartdock.io", "https://docs.smartdock.io/guide/quickstart?x=1")).isTrue();
        assertThat(OriginDomainMatcher.matches("docs.smartdock.io", "https://other.io/docs.smartdock.io")).isFalse();
    }

    @Test
    void semicolonSeparatedRulesAlsoWork() {
        assertThat(OriginDomainMatcher.matches("a.com; b.com", "https://b.com")).isTrue();
        assertThat(OriginDomainMatcher.matches("a.com; b.com", "https://c.com")).isFalse();
    }

    @Test
    void extractHostHandlesSchemePortPathAndCase() {
        assertThat(OriginDomainMatcher.extractHost("HTTPS://Example.COM:443/path?x=1")).isEqualTo("example.com");
        assertThat(OriginDomainMatcher.extractHost("example.com:8080")).isEqualTo("example.com");
        assertThat(OriginDomainMatcher.extractHost("https://sub.site.io")).isEqualTo("sub.site.io");
        assertThat(OriginDomainMatcher.extractHost("null")).isNull();
        assertThat(OriginDomainMatcher.extractHost("")).isNull();
        assertThat(OriginDomainMatcher.extractHost(null)).isNull();
    }
}
