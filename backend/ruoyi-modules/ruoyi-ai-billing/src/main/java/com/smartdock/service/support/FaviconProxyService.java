package com.smartdock.service.support;

import cn.hutool.core.codec.Base64;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 业务反馈批1015c Q6(拍板A)：搜索来源卡 favicon 后端代理。
 *
 * <p>前端不直连外网图标服务——历史教训：google s2 / duckduckgo ip3 等图标聚合域名在
 * 大陆网络/内网整体不可达（glm53 终审 P1 实证：dev DNS Query refused + 直连超时，
 * 生产腾讯云同环境）。改为直抓<b>目标站自身的</b> https://{host}/favicon.ico——搜索来源
 * 站与用户可达性同源（搜索结果里用户点得开的站，后端大概率也抓得到）；抓不到照旧
 * miss→前端字母色块，功能不破。base64 缓存 Redis 7 天。</p>
 *
 * <p>SSRF 防护（三审复核后口径）：host 过字符白名单后仅拼进固定 URL 的 host 位
 * （仅字母数字连字符点，无路径/查询/端口注入面）；DNS 解析后任一地址落回环/私网/
 * 链路本地/组播/通配即拒（纵深防御——host 就是实际请求 host，与聚合源时代不同，
 * 这道闸现在是主防线）。响应体上限 32KB。</p>
 */
@Slf4j
@Component
public class FaviconProxyService {

    private static final Pattern HOST_PATTERN =
        Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+\\.[a-zA-Z]{2,}$");
    private static final Duration CACHE_TTL = Duration.ofDays(7);
    private static final int MAX_BYTES = 32 * 1024;
    private static final int TIMEOUT_MS = 3000;

    /**
     * 抓取（或取缓存）站点图标；任何不合法/失败路径统一返回 null（调用方回 404，
     * 前端 onerror 回退色块）——失败不缓存，避免后端短暂断网导致长期负缓存。
     */
    public byte[] fetch(String host) {
        if (host == null || host.length() > 253 || !HOST_PATTERN.matcher(host).matches()) {
            return null;
        }
        String key = "dcs:favicon:" + host;
        // 缓存读放 try 内（三审 P2）：Redis 抖动按 miss 处理继续出网，不让缓存故障放大成 500
        try {
            String cached = RedisUtils.getCacheObject(key);
            if (cached != null) {
                return Base64.decode(cached);
            }
        } catch (Exception e) {
            log.debug("[FaviconProxy] 缓存读失败按 miss 处理 host={}", host);
        }
        try {
            // DNS 内网解析拒绝（主防线，见类注释 SSRF 口径；组播补充自 m3 P2-4）
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                    || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                    return null;
                }
            }
        } catch (Exception e) {
            return null;
        }
        try (HttpResponse resp = HttpRequest.get("https://" + host + "/favicon.ico")
            .timeout(TIMEOUT_MS).execute()) {
            if (!resp.isOk()) {
                return null;
            }
            byte[] body = resp.bodyBytes();
            if (body == null || body.length == 0 || body.length > MAX_BYTES) {
                return null;
            }
            // 缓存写失败不影响本次返回（三审 P2）：图已经拿到，掉缓存只亏一次重复出网
            try {
                RedisUtils.setCacheObject(key, Base64.encode(body), CACHE_TTL);
            } catch (Exception e) {
                log.debug("[FaviconProxy] 缓存写失败(不影响本次返回) host={}", host);
            }
            return body;
        } catch (Exception e) {
            log.debug("[FaviconProxy] 抓取失败 host={} err={}", host, e.getMessage());
            return null;
        }
    }
}
