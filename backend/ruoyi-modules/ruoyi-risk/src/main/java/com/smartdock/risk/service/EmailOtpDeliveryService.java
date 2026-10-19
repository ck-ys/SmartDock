package com.smartdock.risk.service;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.UserService;
import org.dromara.common.mail.utils.MailUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * O2 delivery: send L4 OTP codes to the user's configured email address.
 */
@Service
public class EmailOtpDeliveryService implements OtpDeliveryService {

    public static final int EMAIL_RECOVERY_REQUIRED_CODE = 4602;
    public static final int EMAIL_DELIVERY_FAILED_CODE = 4603;

    /** 用户公共服务（按 userId 查邮箱） */
    private final UserService userService;
    /** 邮件网关（发送 OTP 验证码邮件） */
    private final MailGateway mailGateway;

    @Autowired
    public EmailOtpDeliveryService(UserService userService) {
        this(userService, (to, subject, body) -> MailUtils.sendText(to, subject, body));
    }

    public EmailOtpDeliveryService(UserService userService, MailGateway mailGateway) {
        this.userService = userService;
        this.mailGateway = mailGateway;
    }

    @Override
    public DeliveryReceipt deliver(Long userId, String code, Duration validFor) {
        String email = userService.selectEmailById(userId);
        email = StrUtil.trim(email);
        int at = email == null ? -1 : email.lastIndexOf('@');
        if (StrUtil.isBlank(email) || at <= 0 || at == email.length() - 1) {
            throw new ServiceException(
                "账号未配置有效邮箱，请联系管理员完成身份恢复",
                EMAIL_RECOVERY_REQUIRED_CODE
            );
        }
        long validMinutes = Math.max(1, validFor.toMinutes());
        String subject = "DCS 安全验证码";
        String body = "您的 DCS 安全验证码为 " + code + "，" + validMinutes
            + " 分钟内有效。请勿向任何人泄露。";
        try {
            mailGateway.send(email, subject, body);
        } catch (Exception e) {
            throw new ServiceException("安全验证码邮件发送失败，请稍后重试", EMAIL_DELIVERY_FAILED_CODE);
        }
        return new DeliveryReceipt(mask(email));
    }

    private String mask(String email) {
        int at = email.lastIndexOf('@');
        String local = email.substring(0, at);
        return local.substring(0, 1) + "***" + email.substring(at);
    }

    @FunctionalInterface
    public interface MailGateway {
        String send(String to, String subject, String body);
    }
}
