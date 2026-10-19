package com.smartdock.risk.service;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import com.smartdock.risk.config.DcsRolloutProperties;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;

/**
 * Issues short-lived opaque captcha passes and consumes them with full binding checks.
 */
@Service
@RequiredArgsConstructor
public class CaptchaPassService {

    public static final int CHALLENGE_REQUIRED_CODE = 4601;

    private static final String CACHE_PREFIX = "dcs:captcha:pass:";
    private static final Duration EXPIRE = Duration.ofMinutes(2);

    private final RedissonClient redissonClient;

    @Autowired(required = false)
    private DcsRolloutProperties rolloutProperties;

    public String issue(PassBinding binding, int verifiedLevel) {
        if (verifiedLevel < RiskScoreService.LEVEL_L1 || verifiedLevel > RiskScoreService.LEVEL_L4) {
            throw new IllegalArgumentException("verifiedLevel must be between 1 and 4");
        }
        String token = IdUtil.fastSimpleUUID();
        CaptchaPass pass = new CaptchaPass(binding, verifiedLevel);
        redissonClient.<CaptchaPass>getBucket(CACHE_PREFIX + token).set(pass, EXPIRE);
        return token;
    }

    public boolean consume(String token, PassBinding expectedBinding, int requiredLevel) {
        if (token == null || token.isBlank() || expectedBinding == null) {
            return false;
        }
        RBucket<CaptchaPass> bucket = redissonClient.getBucket(CACHE_PREFIX + token);
        CaptchaPass actualPass = bucket.get();
        if (actualPass == null
            || !expectedBinding.equals(actualPass.binding())
            || actualPass.verifiedLevel() < requiredLevel) {
            return false;
        }
        return bucket.compareAndSet(actualPass, null);
    }

    public void requirePass(int riskLevel, String token, PassBinding binding) {
        if (riskLevel <= RiskScoreService.LEVEL_L0) {
            return;
        }
        if (rolloutProperties != null && !rolloutProperties.isChallengeEnabled(riskLevel)) {
            return;
        }
        if (!consume(token, binding, riskLevel)) {
            throw new ServiceException("需要完成风险验证", CHALLENGE_REQUIRED_CODE);
        }
    }

    public record CaptchaPass(
        PassBinding binding,
        int verifiedLevel
    ) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;
    }

    public record PassBinding(
        String tenantId,
        Long userId,
        String ip,
        String scene,
        String action
    ) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        public PassBinding {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId must not be blank");
            }
            if (userId == null) {
                throw new IllegalArgumentException("userId must not be null");
            }
            if (ip == null || ip.isBlank()) {
                throw new IllegalArgumentException("ip must not be blank");
            }
            if (scene == null || scene.isBlank()) {
                throw new IllegalArgumentException("scene must not be blank");
            }
            if (action == null || action.isBlank()) {
                throw new IllegalArgumentException("action must not be blank");
            }
        }
    }
}
