package com.smartdock.analytics.quality;

/**
 * IP 地域粗分（纯函数，零外部数据库）。
 *
 * <p>批 7 拍板 Q4-A：明文 IP 不落库，地域只做回环/私网/公网三分——
 * 回环→「本地」，私网段→「内网」，公网→「未知」。公网 GeoIP 解析留 SPI 口子，
 * 批 11 接入合规评估后再决定是否引入 MaxMind 离线库。</p>
 *
 * @author smartdock
 */
public final class IpGeoResolver {

    public static final String LOCAL = "本地";
    public static final String INTRANET = "内网";
    public static final String UNKNOWN = "未知";

    private IpGeoResolver() {
    }

    /**
     * @return 地域名；ip 为空返回 null（调用方跳过写入）
     */
    public static String resolve(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String value = ip.trim().toLowerCase();
        if (value.startsWith("::1") || value.startsWith("127.")) {
            return LOCAL;
        }
        if (value.startsWith("fe80:") || value.startsWith("fc") || value.startsWith("fd")) {
            return INTRANET;
        }
        if (value.startsWith("10.") || value.startsWith("192.168.") || value.startsWith("169.254.")) {
            return INTRANET;
        }
        if (value.startsWith("172.")) {
            int second = secondOctet(value);
            if (second >= 16 && second <= 31) {
                return INTRANET;
            }
        }
        return UNKNOWN;
    }

    private static int secondOctet(String ip) {
        try {
            int firstDot = ip.indexOf('.');
            int secondDot = ip.indexOf('.', firstDot + 1);
            return Integer.parseInt(ip.substring(firstDot + 1, secondDot));
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
