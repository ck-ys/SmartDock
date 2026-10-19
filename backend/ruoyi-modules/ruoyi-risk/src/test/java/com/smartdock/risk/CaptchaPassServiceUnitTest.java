package com.smartdock.risk;

import org.dromara.common.core.exception.ServiceException;
import com.smartdock.risk.service.CaptchaPassService;
import com.smartdock.risk.service.CaptchaPassService.CaptchaPass;
import com.smartdock.risk.service.CaptchaPassService.PassBinding;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class CaptchaPassServiceUnitTest {

    private static final PassBinding BINDING =
        new PassBinding("200001", 7L, "127.0.0.1", "SEND_MSG", "chat:88");

    @Test
    void issuesOpaquePassWithBoundValueAndShortTtl() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<CaptchaPass> bucket = mock(RBucket.class);
        when(client.<CaptchaPass>getBucket(anyString())).thenReturn(bucket);
        CaptchaPassService service = new CaptchaPassService(client);

        String token = service.issue(BINDING, 2);

        assertThat(token).isNotBlank().doesNotContain("200001").doesNotContain("127.0.0.1");
        verify(bucket).set(eq(new CaptchaPass(BINDING, 2)), eq(Duration.ofMinutes(2)));
    }

    @Test
    void consumesMatchingPassExactlyOnce() {
        AtomicReference<CaptchaPass> state =
            new AtomicReference<>(new CaptchaPass(BINDING, 2));
        CaptchaPassService service = serviceWithPassState(state);

        assertThat(service.consume("pass-1", BINDING, 2)).isTrue();
        assertThat(service.consume("pass-1", BINDING, 2)).isFalse();
    }

    @Test
    void rejectsEveryMismatchedBindingFieldWithoutDeletingThePass() {
        List<PassBinding> mismatches = List.of(
            new PassBinding("other", 7L, "127.0.0.1", "SEND_MSG", "chat:88"),
            new PassBinding("200001", 8L, "127.0.0.1", "SEND_MSG", "chat:88"),
            new PassBinding("200001", 7L, "127.0.0.2", "SEND_MSG", "chat:88"),
            new PassBinding("200001", 7L, "127.0.0.1", "UPLOAD", "chat:88"),
            new PassBinding("200001", 7L, "127.0.0.1", "SEND_MSG", "chat:89")
        );

        for (PassBinding mismatch : mismatches) {
            RedissonClient client = mock(RedissonClient.class);
            @SuppressWarnings("unchecked")
            RBucket<CaptchaPass> bucket = mock(RBucket.class);
            when(client.<CaptchaPass>getBucket(anyString())).thenReturn(bucket);
            when(bucket.get()).thenReturn(new CaptchaPass(BINDING, 4));

            assertThat(new CaptchaPassService(client).consume("pass-1", mismatch, 2)).isFalse();
            verify(bucket, never()).compareAndSet(any(), any());
        }
    }

    @Test
    void rejectsExpiredOrMissingPass() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<CaptchaPass> bucket = mock(RBucket.class);
        when(client.<CaptchaPass>getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenReturn(null);

        assertThat(new CaptchaPassService(client).consume("expired", BINDING, 2)).isFalse();
    }

    @Test
    void rejectsIncompleteBindingsBeforeWritingRedis() {
        List<Runnable> invalidBindings = List.of(
            () -> new PassBinding(" ", 7L, "127.0.0.1", "SEND_MSG", "chat:88"),
            () -> new PassBinding("200001", null, "127.0.0.1", "SEND_MSG", "chat:88"),
            () -> new PassBinding("200001", 7L, " ", "SEND_MSG", "chat:88"),
            () -> new PassBinding("200001", 7L, "127.0.0.1", " ", "chat:88"),
            () -> new PassBinding("200001", 7L, "127.0.0.1", "SEND_MSG", " ")
        );

        for (Runnable invalidBinding : invalidBindings) {
            assertThatThrownBy(invalidBinding::run)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void allowsL0WithoutPassAndRequiresValidPassForL1OrHigher() {
        AtomicReference<CaptchaPass> state =
            new AtomicReference<>(new CaptchaPass(BINDING, 2));
        CaptchaPassService service = serviceWithPassState(state);

        assertThatCode(() -> service.requirePass(0, null, BINDING)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requirePass(1, null, BINDING))
            .isInstanceOf(ServiceException.class)
            .extracting("code")
            .isEqualTo(CaptchaPassService.CHALLENGE_REQUIRED_CODE);
        assertThatCode(() -> service.requirePass(2, "pass-1", BINDING)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requirePass(2, "pass-1", BINDING))
            .isInstanceOf(ServiceException.class)
            .extracting("code")
            .isEqualTo(CaptchaPassService.CHALLENGE_REQUIRED_CODE);
    }

    @Test
    void lowerLevelPassCannotSatisfyEscalatedRisk() {
        AtomicReference<CaptchaPass> state =
            new AtomicReference<>(new CaptchaPass(BINDING, 1));
        CaptchaPassService service = serviceWithPassState(state);

        assertThatThrownBy(() -> service.requirePass(4, "pass-l1", BINDING))
            .isInstanceOf(ServiceException.class)
            .extracting("code")
            .isEqualTo(CaptchaPassService.CHALLENGE_REQUIRED_CODE);
        assertThat(state.get()).isNotNull();
    }

    private CaptchaPassService serviceWithPassState(AtomicReference<CaptchaPass> state) {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<CaptchaPass> bucket = mock(RBucket.class);
        when(client.<CaptchaPass>getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenAnswer(invocation -> state.get());
        when(bucket.compareAndSet(any(), eq(null))).thenAnswer(invocation -> {
            CaptchaPass expected = invocation.getArgument(0);
            return state.compareAndSet(expected, null);
        });
        return new CaptchaPassService(client);
    }
}
