package com.smartdock.analytics.quality;

import java.util.Locale;

/**
 * 站点域名白名单匹配（纯函数）。
 *
 * <p>批 7 拍板 Q5-A：请求头缺失（同源请求、curl 联调、老浏览器）一律放行；
 * 带 Origin/Referer 且站点配置了 allowed_domains 时严格匹配。
 * 支持 {@code *.example.com}（匹配任意子域，不含裸域）与精确域名两种规则；
 * 端口与协议不参与比较，"Origin: null"（沙箱 iframe/隐私扩展）按缺失处理。</p>
 *
 * @author smartdock
 */
public final class OriginDomainMatcher {

    private OriginDomainMatcher() {
    }

    /**
     * @param allowedDomains 逗号/分号分隔的白名单；空 = 不限制
     * @param originOrReferer 请求带的来源头（Origin 优先，Referer 兜底）；空或 "null" = 无来源
     * @return true = 放行
     */
    public static boolean matches(String allowedDomains, String originOrReferer) {
        if (allowedDomains == null || allowedDomains.isBlank()) {
            return true;
        }
        String host = extractHost(originOrReferer);
        if (host == null) {
            return true;
        }
        for (String rule : allowedDomains.split("[,;]")) {
            String r = rule.trim().toLowerCase(Locale.ROOT);
            if (r.isEmpty()) {
                continue;
            }
            if (r.startsWith("*.")) {
                String suffix = r.substring(1); // ".example.com"
                if (host.endsWith(suffix)) {
                    return true;
                }
            } else if (r.startsWith(".")) {
                if (host.endsWith(r) || host.equals(r.substring(1))) {
                    return true;
                }
            } else if (r.equals(host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 Origin/Referer 值提取小写 host（去协议、去端口、去路径）；非 URL 形态返回 null。
     */
    public static String extractHost(String originOrReferer) {
        if (originOrReferer == null) {
            return null;
        }
        String value = originOrReferer.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        String rest = value;
        int scheme = rest.indexOf("://");
        if (scheme > -1) {
            rest = rest.substring(scheme + 3);
        }
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                end = i;
                break;
            }
        }
        rest = rest.substring(0, end).toLowerCase(Locale.ROOT);
        return rest.isEmpty() ? null : rest;
    }
}
