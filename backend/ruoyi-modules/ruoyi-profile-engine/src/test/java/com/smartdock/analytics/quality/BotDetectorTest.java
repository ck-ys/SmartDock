package com.smartdock.analytics.quality;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 机器人识别纯函数测试（批7 拍板 Q1-A：静态正则库、不拦 HeadlessChrome）。
 */
@Tag("local")
class BotDetectorTest {

    @Test
    void blankUserAgentIsBot() {
        assertThat(BotDetector.isBot(null)).isTrue();
        assertThat(BotDetector.isBot("")).isTrue();
        assertThat(BotDetector.isBot("   ")).isTrue();
    }

    @Test
    void knownSearchAndSeoBotsAreDetected() {
        assertThat(BotDetector.isBot("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")).isTrue();
        assertThat(BotDetector.isBot("Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)")).isTrue();
        assertThat(BotDetector.isBot("Mozilla/5.0 (compatible; Baiduspider/2.0; +http://www.baidu.com/search/spider.html)")).isTrue();
        assertThat(BotDetector.isBot("Mozilla/5.0 (compatible; YandexBot/3.0; +http://yandex.com/bots)")).isTrue();
        assertThat(BotDetector.isBot("Mozilla/5.0 (compatible; AhrefsBot/7.0; +http://ahrefs.com/robot/)")).isTrue();
        assertThat(BotDetector.isBot("Mozilla/5.0 (compatible; SemrushBot/7~bl; +http://www.semrush.com/bot.html)")).isTrue();
        assertThat(BotDetector.isBot("Mozilla/5.0 (compatible; MJ12bot/v1.4.5; http://www.majestic12.co.uk/bot/bot.php)")).isTrue();
        assertThat(BotDetector.isBot("Sogou web spider/4.0(+http://www.sogou.com/docs/help/webmasters.htm#07)")).isTrue();
        assertThat(BotDetector.isBot("DuckDuckBot/1.0; (+http://duckduckgo.com/duckduckbot.html)")).isTrue();
    }

    @Test
    void scriptClientsAndAutomationFrameworksAreDetected() {
        assertThat(BotDetector.isBot("curl/8.4.0")).isTrue();
        assertThat(BotDetector.isBot("Wget/1.21.3 (linux-gnu)")).isTrue();
        assertThat(BotDetector.isBot("python-requests/2.31.0")).isTrue();
        assertThat(BotDetector.isBot("okhttp/4.12.0")).isTrue();
        assertThat(BotDetector.isBot("Go-http-client/2.0")).isTrue();
        assertThat(BotDetector.isBot("Java/17.0.9")).isTrue();
        assertThat(BotDetector.isBot("Scrapy/2.11 (+https://scrapy.org)")).isTrue();
        assertThat(BotDetector.isBot("axios/1.6.0")).isTrue();
        assertThat(BotDetector.isBot("node-fetch/1.0 (+https://github.com/bitinn/node-fetch)")).isTrue();
    }

    @Test
    void monitoringAndRendererProbesAreDetected() {
        assertThat(BotDetector.isBot("Mozilla/5.0 (compatible; UptimeRobot/2.0; http://www.uptimerobot.com/)")).isTrue();
        assertThat(BotDetector.isBot("Pingdom.com_bot_version_1.4_(http://www.pingdom.com)")).isTrue();
        assertThat(BotDetector.isBot("Mozilla/5.0 Chrome/90.0.0.0 Safari/537.36 (compatible; Lighthouse)")).isTrue();
    }

    @Test
    void headlessChromeIsNotBlocked() {
        // 拍板 Q1-A: 自家 Playwright E2E 与无头真用户依赖 HeadlessChrome, 不拦
        assertThat(BotDetector.isBot(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/120.0.0.0 Safari/537.36")).isFalse();
    }

    @Test
    void realBrowsersAreNotBlocked() {
        assertThat(BotDetector.isBot(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")).isFalse();
        assertThat(BotDetector.isBot(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0")).isFalse();
        assertThat(BotDetector.isBot(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1")).isFalse();
        assertThat(BotDetector.isBot(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0")).isFalse();
        // 同名浏览器与同名爬虫不可混淆: YandexBrowser 是浏览器, YandexBot 是爬虫
        assertThat(BotDetector.isBot(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 YaBrowser/24.1.0 Safari/537.36")).isFalse();
        assertThat(BotDetector.isBot("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")).isFalse();
    }
}
