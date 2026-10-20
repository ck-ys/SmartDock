package com.smartdock.analytics.quality;

import java.util.regex.Pattern;

/**
 * 机器人 UA 识别（纯函数，静态正则库，零外部依赖）。
 *
 * <p>覆盖主流搜索爬虫、SEO 蜘蛛、命令行 HTTP 客户端与常见自动化库；
 * 空 UA 一律视为机器人。批 7 拍板 Q1-A：不拦 HeadlessChrome——
 * 自家 Playwright E2E 与无头真用户都依赖它，误杀代价大于漏杀。</p>
 *
 * @author smartdock
 */
public final class BotDetector {

    /**
     * 关键词按"机器自称"归类: 搜索引擎(bot/spider/crawl 家族)、SEO 工具、HTTP 客户端库、自动化抓取框架。
     * 刻意不含 headless。
     */
    private static final Pattern BOT_PATTERN = Pattern.compile(
        "bot|spider|crawler|crawl|slurp|bingpreview|facebookexternalhit|facebookcatalog|twitterbot|linkedinbot"
            + "|curl|wget|python-requests|python-urllib|okhttp|go-http-client|apache-httpclient|httpclient"
            + "|libwww|java/|scrapy|http-unit|httpx|axios/|node-fetch|postmanruntime|insomnia"
            + "|semrush|ahrefs|mj12|dotbot|petalbot|bytespider|yandexbot|sogou spider|baiduspider|duckduckbot"
            + "|uptime|pingdom|newrelicpinger|monitoring|statuscake|site24x7|rendertron|lighthouse",
        Pattern.CASE_INSENSITIVE);

    private BotDetector() {
    }

    /**
     * @return true = 机器人（整批拒绝，留痕 reason=robot）
     */
    public static boolean isBot(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return true;
        }
        return BOT_PATTERN.matcher(userAgent).find();
    }
}
