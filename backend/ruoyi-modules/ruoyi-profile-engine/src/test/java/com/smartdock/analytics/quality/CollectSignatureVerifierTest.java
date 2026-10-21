package com.smartdock.analytics.quality;

import com.smartdock.analytics.domain.AnalyticsSite;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 采集签名验证测试(批11 Q3): 时间窗/签名串/nonce 防重放/开关兼容。
 */
@Tag("local")
class CollectSignatureVerifierTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RBucket<String> bucket = mock(RBucket.class);
    private final CollectSignatureVerifier verifier = new CollectSignatureVerifier(redisson);

    private AnalyticsSite site() {
        AnalyticsSite s = new AnalyticsSite();
        s.setSiteKey("sd_demo_site");
        s.setSignEnabled("1");
        s.setSignSecret("s3cret");
        return s;
    }

    private String sign(String ts, String nonce, String body) {
        return CollectSignatureVerifier.hmac("s3cret", ts, nonce, "sd_demo_site", body);
    }

    @Test
    void validSignaturePasses() {
        org.mockito.Mockito.doReturn(bucket).when(redisson).getBucket(anyString());
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        String ts = String.valueOf(System.currentTimeMillis());
        String body = "{\"siteKey\":\"sd_demo_site\",\"events\":[]}";
        String sig = sign(ts, "n1", body);
        assertThat(verifier.verify(site(),
            new CollectRequestContext("ua", null, null, "1.2.3.4", ts, "n1", sig), body)).isNull();
    }

    @Test
    void staleTimestampRejected() {
        String ts = String.valueOf(System.currentTimeMillis() - 11 * 60_000L);
        String body = "{}";
        String sig = sign(ts, "n2", body);
        assertThat(verifier.verify(site(),
            new CollectRequestContext("ua", null, null, "1.2.3.4", ts, "n2", sig), body))
            .isEqualTo("signature_stale");
    }

    @Test
    void tamperedBodyRejected() {
        org.mockito.Mockito.doReturn(bucket).when(redisson).getBucket(anyString());
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(true);
        String ts = String.valueOf(System.currentTimeMillis());
        String sig = sign(ts, "n3", "{\"events\":[1]}");
        assertThat(verifier.verify(site(),
            new CollectRequestContext("ua", null, null, "1.2.3.4", ts, "n3", sig), "{\"events\":[2]}"))
            .isEqualTo("signature_invalid");
    }

    @Test
    void replayedNonceRejected() {
        org.mockito.Mockito.doReturn(bucket).when(redisson).getBucket(anyString());
        when(bucket.setIfAbsent(anyString(), any(Duration.class))).thenReturn(false);
        String ts = String.valueOf(System.currentTimeMillis());
        String body = "{}";
        String sig = sign(ts, "n4", body);
        assertThat(verifier.verify(site(),
            new CollectRequestContext("ua", null, null, "1.2.3.4", ts, "n4", sig), body))
            .isEqualTo("signature_replayed");
    }

    @Test
    void missingHeadersRejected() {
        assertThat(verifier.verify(site(), CollectRequestContext.empty(), "{}"))
            .isEqualTo("signature_missing");
    }

    @Test
    void disabledSiteSkipsVerification() {
        AnalyticsSite s = site();
        s.setSignEnabled("0");
        assertThat(verifier.verify(s, CollectRequestContext.empty(), "{}")).isNull();
    }

    @Test
    void hmacIsDeterministicAndSecretBound() {
        String a = CollectSignatureVerifier.hmac("k1", "1", "n", "site", "b");
        String b = CollectSignatureVerifier.hmac("k2", "1", "n", "site", "b");
        assertThat(a).hasSize(64);
        assertThat(a).isNotEqualTo(b);
        assertThat(a).isEqualTo(CollectSignatureVerifier.hmac("k1", "1", "n", "site", "b"));
    }
}
