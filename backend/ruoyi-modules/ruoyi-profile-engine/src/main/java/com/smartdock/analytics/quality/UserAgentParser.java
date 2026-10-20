package com.smartdock.analytics.quality;

/**
 * 服务端 UA 解析：设备类型 / 浏览器 / 操作系统（纯函数，不依赖客户端上报）。
 *
 * <p>只覆盖主流 UA，解析不出返回 "unknown"；调用方按"解析出确定值才覆盖"使用，
 * 保证不降级客户端上下文。完整 UA 猜机型不在本版范围。</p>
 *
 * @author smartdock
 */
public final class UserAgentParser {

    public static final String UNKNOWN = "unknown";

    private UserAgentParser() {
    }

    /**
     * 解析结果：任何一项解析不出即为 "unknown"。
     */
    public record UaInfo(String deviceType, String os, String browser) {

        public boolean resolved() {
            return !UNKNOWN.equals(deviceType) || !UNKNOWN.equals(os) || !UNKNOWN.equals(browser);
        }
    }

    public static UaInfo parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new UaInfo(UNKNOWN, UNKNOWN, UNKNOWN);
        }
        String ua = userAgent.toLowerCase();
        return new UaInfo(deviceType(ua), os(ua), browser(ua));
    }

    private static String deviceType(String ua) {
        if (ua.contains("ipad") || ua.contains("tablet") || ua.contains("playbook") || ua.contains("silk")) {
            return "tablet";
        }
        if (ua.contains("mobile") || ua.contains("android") || ua.contains("iphone")
            || ua.contains("ipod") || ua.contains("windows phone")) {
            return "mobile";
        }
        if (ua.contains("mozilla") || ua.contains("opera") || ua.contains("chrome")
            || ua.contains("safari") || ua.contains("firefox") || ua.contains("edge")) {
            return "desktop";
        }
        return UNKNOWN;
    }

    private static String os(String ua) {
        if (ua.contains("windows")) {
            return "Windows";
        }
        if (ua.contains("android")) {
            return "Android";
        }
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")) {
            return "iOS";
        }
        if (ua.contains("mac os x") || ua.contains("macintosh")) {
            return "macOS";
        }
        if (ua.contains("cros")) {
            return "ChromeOS";
        }
        if (ua.contains("linux") || ua.contains("x11")) {
            return "Linux";
        }
        return UNKNOWN;
    }

    private static String browser(String ua) {
        // 顺序敏感: Edg/OPR 在 Chrome 引擎串之前, Yandex 在 Safari 之前
        if (ua.contains("edg/") || ua.contains("edge/")) {
            return "Edge";
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("samsungbrowser")) {
            return "Samsung Internet";
        }
        if (ua.contains("miuibrowser")) {
            return "MIUI Browser";
        }
        if (ua.contains("firefox/") || ua.contains("fxios")) {
            return "Firefox";
        }
        if (ua.contains("chrome/") || ua.contains("crios")) {
            return "Chrome";
        }
        if (ua.contains("safari/")) {
            return "Safari";
        }
        return UNKNOWN;
    }
}
