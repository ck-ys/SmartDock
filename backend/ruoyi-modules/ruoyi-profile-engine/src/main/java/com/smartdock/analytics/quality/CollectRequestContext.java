package com.smartdock.analytics.quality;

/**
 * 采集请求上下文快照：控制器从请求头提取，服务层消费（可空，便于单测与脱头调用）。
 *
 * @param userAgent User-Agent 头
 * @param origin Origin 头（跨域上报才有）
 * @param referer Referer 头
 * @param clientIp 客户端 IP（限流/哈希/地域用，明文不落库）
 * @author smartdock
 */
public record CollectRequestContext(String userAgent, String origin, String referer, String clientIp) {

    public static CollectRequestContext empty() {
        return new CollectRequestContext(null, null, null, null);
    }
}
