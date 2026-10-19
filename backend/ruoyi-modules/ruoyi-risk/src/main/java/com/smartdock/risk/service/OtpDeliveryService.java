package com.smartdock.risk.service;

import java.time.Duration;

/**
 * Delivery boundary for L4 one-time passwords.
 *
 * <p>Email is the only production implementation for now. A future SMS
 * implementation can be added without changing the captcha workflow.</p>
 */
public interface OtpDeliveryService {

    /** 向指定用户投递一次性口令(当前仅邮件实现),validFor 指定有效期。 */
    DeliveryReceipt deliver(Long userId, String code, Duration validFor);

    record DeliveryReceipt(String deliveryHint) {
    }
}
