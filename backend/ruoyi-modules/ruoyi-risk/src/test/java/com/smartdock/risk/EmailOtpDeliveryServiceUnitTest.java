package com.smartdock.risk;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.UserService;
import com.smartdock.risk.service.EmailOtpDeliveryService;
import com.smartdock.risk.service.OtpDeliveryService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class EmailOtpDeliveryServiceUnitTest {

    @Test
    void sendsOtpToUserEmailAndReturnsOnlyMaskedHint() {
        UserService userService = mock(UserService.class);
        when(userService.selectEmailById(7L)).thenReturn("alice@example.com");
        AtomicReference<String> sentTo = new AtomicReference<>();
        AtomicReference<String> sentBody = new AtomicReference<>();
        EmailOtpDeliveryService service = new EmailOtpDeliveryService(
            userService,
            (to, subject, body) -> {
                sentTo.set(to);
                sentBody.set(body);
                return "message-id";
            }
        );

        OtpDeliveryService.DeliveryReceipt receipt =
            service.deliver(7L, "123456", Duration.ofMinutes(5));

        verify(userService).selectEmailById(7L);
        assertThat(sentTo.get()).isEqualTo("alice@example.com");
        assertThat(sentBody.get()).contains("123456").contains("5");
        assertThat(receipt.deliveryHint()).isEqualTo("a***@example.com");
        assertThat(receipt.toString()).doesNotContain("123456");
    }

    @Test
    void missingEmailRequiresManualRecovery() {
        UserService userService = mock(UserService.class);
        when(userService.selectEmailById(7L)).thenReturn(" ");
        EmailOtpDeliveryService service = new EmailOtpDeliveryService(
            userService,
            (to, subject, body) -> "unused"
        );

        assertThatThrownBy(() -> service.deliver(7L, "123456", Duration.ofMinutes(5)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("邮箱")
            .extracting("code")
            .isEqualTo(EmailOtpDeliveryService.EMAIL_RECOVERY_REQUIRED_CODE);
    }

    @Test
    void malformedEmailIsRejectedBeforeCallingMailGateway() {
        UserService userService = mock(UserService.class);
        when(userService.selectEmailById(7L)).thenReturn("@example.com");
        AtomicReference<String> sentTo = new AtomicReference<>();
        EmailOtpDeliveryService service = new EmailOtpDeliveryService(
            userService,
            (to, subject, body) -> {
                sentTo.set(to);
                return "unused";
            }
        );

        assertThatThrownBy(() -> service.deliver(7L, "123456", Duration.ofMinutes(5)))
            .isInstanceOf(ServiceException.class)
            .extracting("code")
            .isEqualTo(EmailOtpDeliveryService.EMAIL_RECOVERY_REQUIRED_CODE);
        assertThat(sentTo.get()).isNull();
    }
}
