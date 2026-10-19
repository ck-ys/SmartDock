package com.smartdock.risk;

import com.smartdock.risk.mapper.DcsCaptchaLogMapper;
import com.smartdock.risk.service.CaptchaPassService;
import com.smartdock.risk.service.CaptchaPassService.PassBinding;
import com.smartdock.risk.service.CaptchaService;
import com.smartdock.risk.service.CaptchaService.CaptchaChallenge;
import com.smartdock.risk.service.OtpDeliveryService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class CaptchaServiceUnitTest {

    private static final PassBinding BINDING =
        new PassBinding("200001", 7L, "127.0.0.1", "SEND_MSG", "chat:88");

    @Test
    void storesBoundChallengeWithHashesInsteadOfPlainAnswers() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<CaptchaChallenge> bucket = mock(RBucket.class);
        when(client.<CaptchaChallenge>getBucket(anyString())).thenReturn(bucket);
        CaptchaService service = service(client, mock(CaptchaPassService.class));

        CaptchaService.CaptchaResult result = service.generate(1, BINDING);

        ArgumentCaptor<CaptchaChallenge> captor = ArgumentCaptor.forClass(CaptchaChallenge.class);
        verify(bucket).set(captor.capture(), eq(Duration.ofSeconds(60)));
        CaptchaChallenge challenge = captor.getValue();
        assertThat(challenge.tenantId()).isEqualTo("200001");
        assertThat(challenge.userId()).isEqualTo(7L);
        assertThat(challenge.ip()).isEqualTo("127.0.0.1");
        assertThat(challenge.scene()).isEqualTo("SEND_MSG");
        assertThat(challenge.action()).isEqualTo("chat:88");
        assertThat(challenge.answerHash()).hasSize(64);
        assertThat(challenge.otpHash()).isNull();
        assertThat(result).hasNoNullFieldsOrPropertiesExcept("deliveryHint");
    }

    @Test
    void verifiedImageChallengeReturnsBoundOneTimePass() {
        String token = "image-token";
        CaptchaChallenge challenge = challenge(token, "ABCD", null, 1, BINDING);
        AtomicReference<CaptchaChallenge> state = new AtomicReference<>(challenge);
        RedissonClient client = clientWithState(state);
        CaptchaPassService passService = mock(CaptchaPassService.class);
        when(passService.issue(BINDING, 1)).thenReturn("pass-1");

        CaptchaService.VerificationResult result = service(client, passService)
            .verify(token, "abcd", BINDING);

        assertThat(result.verified()).isTrue();
        assertThat(result.needOtp()).isFalse();
        assertThat(result.captchaPassToken()).isEqualTo("pass-1");
        assertThat(state.get()).isNull();
    }

    @Test
    void rejectsChallengeBoundToAnotherActionWithoutConsumingIt() {
        String token = "image-token";
        CaptchaChallenge challenge = challenge(token, "ABCD", null, 1, BINDING);
        AtomicReference<CaptchaChallenge> state = new AtomicReference<>(challenge);
        RedissonClient client = clientWithState(state);
        CaptchaPassService passService = mock(CaptchaPassService.class);
        PassBinding otherAction =
            new PassBinding("200001", 7L, "127.0.0.1", "SEND_MSG", "chat:89");

        CaptchaService.VerificationResult result = service(client, passService)
            .verify(token, "ABCD", otherAction);

        assertThat(result.verified()).isFalse();
        assertThat(state.get()).isEqualTo(challenge);
        verify(passService, never()).issue(any(), anyInt());
    }

    @Test
    void l4RequiresImageThenOtpBeforeIssuingPass() {
        String token = "l4-token";
        CaptchaChallenge challenge = challenge(token, "ABCDEF", "123456", 4, BINDING);
        AtomicReference<CaptchaChallenge> state = new AtomicReference<>(challenge);
        RedissonClient client = clientWithState(state);
        CaptchaPassService passService = mock(CaptchaPassService.class);
        when(passService.issue(BINDING, 4)).thenReturn("pass-l4");
        CaptchaService service = service(client, passService);

        CaptchaService.VerificationResult imageResult = service.verify(token, "ABCDEF", BINDING);

        assertThat(imageResult.verified()).isTrue();
        assertThat(imageResult.needOtp()).isTrue();
        assertThat(imageResult.captchaPassToken()).isNull();
        assertThat(state.get().answerHash()).isNull();
        verify(passService, never()).issue(any(), anyInt());

        CaptchaService.VerificationResult otpResult = service.verifyOtp(token, "123456", BINDING);

        assertThat(otpResult.captchaPassToken()).isEqualTo("pass-l4");
        assertThat(state.get()).isNull();
    }

    @Test
    void wrongAnswerIncrementsFailureCountWithoutStoringTheAnswer() {
        String token = "image-token";
        CaptchaChallenge challenge = challenge(token, "ABCD", null, 1, BINDING);
        AtomicReference<CaptchaChallenge> state = new AtomicReference<>(challenge);

        CaptchaService.VerificationResult result = service(
            clientWithState(state),
            mock(CaptchaPassService.class)
        ).verify(token, "WRONG", BINDING);

        assertThat(result.verified()).isFalse();
        assertThat(state.get().failedAttempts()).isEqualTo(1);
        assertThat(state.get().answerHash()).isEqualTo(challenge.answerHash());
    }

    @Test
    void l4GenerationDeliversOtpAndNeverReturnsIt() {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<CaptchaChallenge> bucket = mock(RBucket.class);
        when(client.<CaptchaChallenge>getBucket(anyString())).thenReturn(bucket);
        OtpDeliveryService deliveryService = mock(OtpDeliveryService.class);
        when(deliveryService.deliver(eq(7L), anyString(), eq(Duration.ofSeconds(300))))
            .thenReturn(new OtpDeliveryService.DeliveryReceipt("a***@example.com"));
        CaptchaService service = new CaptchaService(
            mock(DcsCaptchaLogMapper.class),
            mock(CaptchaPassService.class),
            client,
            deliveryService
        );

        CaptchaService.CaptchaResult result = service.generate(4, BINDING);

        assertThat(result.deliveryHint()).isEqualTo("a***@example.com");
        assertThat(Arrays.stream(CaptchaService.CaptchaResult.class.getRecordComponents())
            .map(component -> component.getName()))
            .doesNotContain("otpCode");
    }

    private CaptchaService service(RedissonClient client, CaptchaPassService passService) {
        return new CaptchaService(
            mock(DcsCaptchaLogMapper.class),
            passService,
            client,
            mock(OtpDeliveryService.class)
        );
    }

    private RedissonClient clientWithState(AtomicReference<CaptchaChallenge> state) {
        RedissonClient client = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<CaptchaChallenge> bucket = mock(RBucket.class);
        when(client.<CaptchaChallenge>getBucket(anyString())).thenReturn(bucket);
        when(bucket.get()).thenAnswer(invocation -> state.get());
        when(bucket.compareAndSet(any(), any())).thenAnswer(invocation -> {
            CaptchaChallenge expected = invocation.getArgument(0);
            CaptchaChallenge replacement = invocation.getArgument(1);
            return state.compareAndSet(expected, replacement);
        });
        return client;
    }

    private CaptchaChallenge challenge(
        String token,
        String answer,
        String otp,
        int level,
        PassBinding binding
    ) {
        return new CaptchaChallenge(
            binding.tenantId(),
            binding.userId(),
            binding.ip(),
            binding.scene(),
            binding.action(),
            level,
            answer == null ? null : hash(token, answer),
            otp == null ? null : hash(token, otp),
            0
        );
    }

    private String hash(String token, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                (token + ":" + value.trim().toUpperCase()).getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
