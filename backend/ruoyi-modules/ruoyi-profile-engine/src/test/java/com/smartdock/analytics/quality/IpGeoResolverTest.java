package com.smartdock.analytics.quality;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IP 地域粗分纯函数测试（批7 拍板 Q4-A：明文不落库，只做回环/私网/公网三分）。
 */
@Tag("local")
class IpGeoResolverTest {

    @Test
    void loopbackIsLocal() {
        assertThat(IpGeoResolver.resolve("127.0.0.1")).isEqualTo(IpGeoResolver.LOCAL);
        assertThat(IpGeoResolver.resolve("::1")).isEqualTo(IpGeoResolver.LOCAL);
        assertThat(IpGeoResolver.resolve("127.5.4.3")).isEqualTo(IpGeoResolver.LOCAL);
    }

    @Test
    void privateRangesAreIntranet() {
        assertThat(IpGeoResolver.resolve("10.0.0.5")).isEqualTo(IpGeoResolver.INTRANET);
        assertThat(IpGeoResolver.resolve("192.168.3.131")).isEqualTo(IpGeoResolver.INTRANET);
        assertThat(IpGeoResolver.resolve("172.16.0.1")).isEqualTo(IpGeoResolver.INTRANET);
        assertThat(IpGeoResolver.resolve("172.31.255.255")).isEqualTo(IpGeoResolver.INTRANET);
        assertThat(IpGeoResolver.resolve("169.254.1.1")).isEqualTo(IpGeoResolver.INTRANET);
        assertThat(IpGeoResolver.resolve("fe80::1")).isEqualTo(IpGeoResolver.INTRANET);
        assertThat(IpGeoResolver.resolve("fd00::8")).isEqualTo(IpGeoResolver.INTRANET);
    }

    @Test
    void publicIpIsUnknownUntilGeoProviderArrives() {
        // 172.15/172.32 在私网段边界之外, 是公网
        assertThat(IpGeoResolver.resolve("172.15.0.1")).isEqualTo(IpGeoResolver.UNKNOWN);
        assertThat(IpGeoResolver.resolve("172.32.0.1")).isEqualTo(IpGeoResolver.UNKNOWN);
        assertThat(IpGeoResolver.resolve("8.8.8.8")).isEqualTo(IpGeoResolver.UNKNOWN);
        assertThat(IpGeoResolver.resolve("2001:db8::1")).isEqualTo(IpGeoResolver.UNKNOWN);
    }

    @Test
    void blankIpReturnsNullForCallerToSkip() {
        assertThat(IpGeoResolver.resolve(null)).isNull();
        assertThat(IpGeoResolver.resolve(" ")).isNull();
    }

    @Test
    void malformed172StillSafe() {
        // 172 后面解析失败不能抛错, 按公网未知处理
        assertThat(IpGeoResolver.resolve("172.x")).isEqualTo(IpGeoResolver.UNKNOWN);
    }
}
