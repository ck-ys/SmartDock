package com.smartdock.analytics.quality;

import com.smartdock.analytics.domain.AnalyticsSite;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 采集口请求签名验证（批11 Q3）：站点级开关，强制时无签名/过期/重放/伪造整批拒绝。
 *
 * <p>签名串 = timestamp + "\n" + nonce + "\n" + siteKey + "\n" + 原始 JSON 请求体，
 * HMAC-SHA256 后 hex 小写。时间窗 ±10 分钟；nonce 在窗口期内单次有效
 * （Redis setIfAbsent + TTL），同 nonce 重放即拒。开关关闭时全部放行（兼容存量 SDK）。</p>
 *
 * @author smartdock
 */
@Component
@RequiredArgsConstructor
public class CollectSignatureVerifier {

    static final Duration WINDOW = Duration.ofMinutes(10);

    private final RedissonClient redisson;

    /** @return null=通过(或未启用); 否则返回拒绝原因(进采集质量留痕) */
    public String verify(AnalyticsSite site, CollectRequestContext ctx, String rawBody) {
        if (site == null || !"1".equals(site.getSignEnabled())) {
            return null;
        }
        if (StringUtils.isBlank(site.getSignSecret())) {
            return "signature_not_configured";
        }
        String ts = ctx == null ? null : ctx.signTimestamp();
        String nonce = ctx == null ? null : ctx.signNonce();
        String given = ctx == null ? null : ctx.signSignature();
        if (StringUtils.isBlank(ts) || StringUtils.isBlank(nonce) || StringUtils.isBlank(given)) {
            return "signature_missing";
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(ts.trim());
        } catch (NumberFormatException e) {
            return "signature_malformed";
        }
        long skew = Math.abs(System.currentTimeMillis() - timestamp);
        if (skew > WINDOW.toMillis()) {
            return "signature_stale";
        }
        String expected = hmac(site.getSignSecret(), ts.trim(), nonce.trim(),
            site.getSiteKey(), rawBody == null ? "" : rawBody);
        // 批11 回修(终审 P2): 常量时间比较防时序侧信道
        if (!java.security.MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), given.trim().getBytes(StandardCharsets.UTF_8))) {
            return "signature_invalid";
        }
        // 真正防重放: 同 (站点, nonce) 在时间窗内只认第一次
        // 批11 回修(终审 P2): TTL=2×窗口——客户端时钟超前时 nonce 键比签名早过期会开重放缝
        RBucket<String> bucket = redisson.getBucket(
            "analytics:collect:nonce:" + site.getSiteKey() + ":" + nonce.trim());
        if (!bucket.setIfAbsent("1", WINDOW.multipliedBy(2))) {
            return "signature_replayed";
        }
        return null;
    }

    static String hmac(String secret, String ts, String nonce, String siteKey, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String payload = ts + "\n" + nonce + "\n" + siteKey + "\n" + body;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }
}
