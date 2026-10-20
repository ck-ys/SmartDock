package com.smartdock.analytics.quality;

import com.smartdock.analytics.quality.UserAgentParser.UaInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 服务端 UA 解析纯函数测试（批7 拍板 Q8-A：服务端解析优先，解析不出不覆盖）。
 */
@Tag("local")
class UserAgentParserTest {

    @Test
    void windowsChromeDesktop() {
        UaInfo ua = UserAgentParser.parse(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        assertThat(ua.deviceType()).isEqualTo("desktop");
        assertThat(ua.os()).isEqualTo("Windows");
        assertThat(ua.browser()).isEqualTo("Chrome");
        assertThat(ua.resolved()).isTrue();
    }

    @Test
    void edgeDetectedBeforeChromeToken() {
        // Edge UA 同时含 Chrome 与 Edg, 顺序敏感必须先判 Edg
        UaInfo ua = UserAgentParser.parse(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0");
        assertThat(ua.browser()).isEqualTo("Edge");
    }

    @Test
    void iPhoneSafariMobile() {
        UaInfo ua = UserAgentParser.parse(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1");
        assertThat(ua.deviceType()).isEqualTo("mobile");
        assertThat(ua.os()).isEqualTo("iOS");
        assertThat(ua.browser()).isEqualTo("Safari");
    }

    @Test
    void androidPhoneIsMobileEvenWithoutMobileToken() {
        UaInfo ua = UserAgentParser.parse(
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        assertThat(ua.deviceType()).isEqualTo("mobile");
        assertThat(ua.os()).isEqualTo("Android");
        assertThat(ua.browser()).isEqualTo("Chrome");
    }

    @Test
    void ipadIsTablet() {
        UaInfo ua = UserAgentParser.parse(
            "Mozilla/5.0 (iPad; CPU OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Safari/604.1");
        assertThat(ua.deviceType()).isEqualTo("tablet");
        assertThat(ua.os()).isEqualTo("iOS");
    }

    @Test
    void firefoxAndMacOs() {
        UaInfo ua = UserAgentParser.parse(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:121.0) Gecko/20100101 Firefox/121.0");
        assertThat(ua.deviceType()).isEqualTo("desktop");
        assertThat(ua.os()).isEqualTo("macOS");
        assertThat(ua.browser()).isEqualTo("Firefox");
    }

    @Test
    void nonBrowserUaStaysUnknownWithoutResolved() {
        UaInfo ua = UserAgentParser.parse("curl/8.4.0");
        assertThat(ua.deviceType()).isEqualTo(UserAgentParser.UNKNOWN);
        assertThat(ua.os()).isEqualTo(UserAgentParser.UNKNOWN);
        assertThat(ua.browser()).isEqualTo(UserAgentParser.UNKNOWN);
        assertThat(ua.resolved()).isFalse();
    }

    @Test
    void blankUaIsAllUnknown() {
        UaInfo ua = UserAgentParser.parse("  ");
        assertThat(ua.resolved()).isFalse();
    }
}
