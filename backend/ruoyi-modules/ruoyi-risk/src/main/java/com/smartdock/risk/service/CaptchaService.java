package com.smartdock.risk.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.risk.domain.DcsCaptchaLog;
import com.smartdock.risk.mapper.DcsCaptchaLogMapper;
import com.smartdock.risk.service.CaptchaPassService.PassBinding;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

/**
 * 验证码生成与校验服务(B5)。
 *
 * <p><b>难度分级</b>(参考 hCaptcha / 腾讯验证码):
 * <ul>
 *   <li><b>L1</b>: 4 位数字字母 + 弱干扰线</li>
 *   <li><b>L2</b>: 点选文字(返回含 N 个汉字的图, 按顺序点击; 当前实现简化为 5 位 + 强干扰)</li>
 *   <li><b>L3</b>: 6 位扭曲字符 + 强干扰线 + 背景噪点</li>
 *   <li><b>L4</b>: 复用 L3 + 邮箱 OTP 二次验证</li>
 * </ul></p>
 *
 * <p><b>存储</b>: Redis 仅保存绑定信息和答案哈希；完整校验后原子删除并签发一次性通行证。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaService {

    private static final String CACHE_PREFIX = "dcs:captcha:";
    private static final Duration EXPIRE = Duration.ofSeconds(60);
    private static final Duration OTP_EXPIRE = Duration.ofSeconds(300);
    private static final int MAX_FAILED_ATTEMPTS = 5;

    /** 验证码日志 Mapper(记录生成/校验) */
    private final DcsCaptchaLogMapper captchaLogMapper;
    /** 验证码通行证服务(签发一次性 pass) */
    private final CaptchaPassService captchaPassService;
    /** Redisson 客户端(缓存挑战/答案哈希) */
    private final RedissonClient redissonClient;
    /** OTP 投递服务(L4 邮箱二次验证) */
    private final OtpDeliveryService otpDeliveryService;

    /**
     * 生成验证码图片。
     *
     * @param level   难度等级 1-4
     * @param binding 当前用户和受保护动作的完整绑定
     * @return {token, imageBase64, level, needOtp(仅 L4 返回 true)}
     */
    public CaptchaResult generate(int level, PassBinding binding) {
        // 步骤1: 校验难度等级合法区间, 越界直接拒绝
        if (level < 1 || level > 4) {
            throw new IllegalArgumentException("captcha level must be between 1 and 4");
        }
        // 步骤2: 图片难度封顶 L3(L4 复用 L3 图形 + OTP), 并按等级定字符位数
        int imageLevel = Math.min(level, 3);
        int length = imageLevel == 1 ? 4 : (imageLevel == 2 ? 5 : 6);
        // 用大写字母+数字(排除易混淆的 I/O/0/1), 自己拼避免依赖 hutool 版本差异
        String pool = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        // 步骤3: 从字符池随机抽取, 拼出明文验证码
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(pool.charAt(RandomUtil.randomInt(pool.length())));
        }
        String code = sb.toString();
        // 步骤4: 渲染干扰图并转 Base64, 供前端 <img> 直接展示
        BufferedImage img = renderImage(code, imageLevel);
        String base64 = toBase64(img);

        // 步骤5: 生成唯一 token 作为本次挑战的标识, 同时判定是否需要 L4 的邮箱 OTP 二次验证
        String token = IdUtil.fastSimpleUUID();
        boolean needOtp = level == 4;
        // 步骤6: L4 才生成 6 位数字 OTP 并投递到绑定渠道, 非 L4 跳过
        String otpCode = needOtp ? RandomUtil.randomNumbers(6) : null;
        OtpDeliveryService.DeliveryReceipt deliveryReceipt = needOtp
            ? otpDeliveryService.deliver(binding.userId(), otpCode, OTP_EXPIRE)
            : null;
        // 步骤7: 组装挑战对象, 答案与 OTP 均以 token+原值的哈希存储, 避免明文落盘
        CaptchaChallenge challenge = new CaptchaChallenge(
            binding.tenantId(),
            binding.userId(),
            binding.ip(),
            binding.scene(),
            binding.action(),
            level,
            hash(token, code),
            otpCode == null ? null : hash(token, otpCode),
            0
        );
        // 步骤8: 写入 Redis(L4 用更长有效期等 OTP), 后续 verify 以 token 取出校验
        Duration expire = needOtp ? OTP_EXPIRE : EXPIRE;
        redissonClient.<CaptchaChallenge>getBucket(CACHE_PREFIX + token).set(challenge, expire);

        // 写日志
        writeLog(token, level, binding, null);

        // 步骤9: 返回 token、图片、等级与 OTP 投递提示给前端
        log.info("[Captcha] generated tenant={} user={} level={} trace={} length={} otpRequired={}",
            binding.tenantId(), binding.userId(), level, traceToken(token), length, needOtp);
        return new CaptchaResult(
            token,
            base64,
            level,
            needOtp,
            deliveryReceipt == null ? null : deliveryReceipt.deliveryHint()
        );
    }

    /**
     * 校验验证码。
     *
     * @param token  generate 返回的 token
     * @param answer 用户输入的答案
     * @return true=校验成功
     */
    public VerificationResult verify(String token, String answer, PassBinding binding) {
        if (token == null || token.isBlank() || answer == null || answer.isBlank()) {
            return VerificationResult.rejected();
        }
        RBucket<CaptchaChallenge> bucket = redissonClient.getBucket(CACHE_PREFIX + token);
        CaptchaChallenge challenge = bucket.get();
        if (challenge == null) {
            log.warn("[Captcha] rejected trace={} reason=expired_or_missing", traceToken(token));
            updateLog(token, false, "已过期");
            return VerificationResult.rejected();
        }
        if (!challenge.matches(binding) || challenge.answerHash() == null) {
            return VerificationResult.rejected();
        }
        if (!challenge.answerHash().equals(hash(token, answer))) {
            recordFailure(bucket, challenge, token, "答案错误");
            return VerificationResult.rejected();
        }
        if (challenge.level() == 4) {
            CaptchaChallenge otpChallenge = challenge.withAnswerVerified();
            boolean updated = bucket.compareAndSet(challenge, otpChallenge);
            return updated ? VerificationResult.waitingForOtp() : VerificationResult.rejected();
        }
        if (!bucket.compareAndSet(challenge, null)) {
            return VerificationResult.rejected();
        }
        String passToken = captchaPassService.issue(binding, challenge.level());
        updateLog(token, true, null);
        log.info("[Captcha] image verified tenant={} user={} level={} trace={}",
            binding.tenantId(), binding.userId(), challenge.level(), traceToken(token));
        return VerificationResult.passed(passToken);
    }

    /**
     * 校验 L4 的 OTP。
     */
    public VerificationResult verifyOtp(String token, String otpCode, PassBinding binding) {
        if (token == null || token.isBlank() || otpCode == null || otpCode.isBlank()) {
            return VerificationResult.rejected();
        }
        RBucket<CaptchaChallenge> bucket = redissonClient.getBucket(CACHE_PREFIX + token);
        CaptchaChallenge challenge = bucket.get();
        if (challenge == null
            || challenge.level() != 4
            || challenge.answerHash() != null
            || challenge.otpHash() == null
            || !challenge.matches(binding)) {
            return VerificationResult.rejected();
        }
        if (!challenge.otpHash().equals(hash(token, otpCode))) {
            recordFailure(bucket, challenge, token, "OTP 错误");
            return VerificationResult.rejected();
        }
        if (!bucket.compareAndSet(challenge, null)) {
            return VerificationResult.rejected();
        }
        String passToken = captchaPassService.issue(binding, challenge.level());
        updateLog(token, true, null);
        log.info("[Captcha] otp verified tenant={} user={} level=4 trace={}",
            binding.tenantId(), binding.userId(), traceToken(token));
        return VerificationResult.passed(passToken);
    }

    private void recordFailure(
        RBucket<CaptchaChallenge> bucket,
        CaptchaChallenge challenge,
        String token,
        String reason
    ) {
        int failedAttempts = challenge.failedAttempts() + 1;
        CaptchaChallenge replacement = failedAttempts >= MAX_FAILED_ATTEMPTS
            ? null
            : challenge.withFailedAttempts(failedAttempts);
        bucket.compareAndSet(challenge, replacement);
        updateLog(token, false, failedAttempts >= MAX_FAILED_ATTEMPTS ? "失败次数过多" : reason);
        log.warn("[Captcha] rejected tenant={} user={} level={} trace={} attempts={} reason={}",
            challenge.tenantId(), challenge.userId(), challenge.level(), traceToken(token), failedAttempts, reason);
    }

    private String traceToken(String token) {
        if (token == null || token.isBlank()) return "-";
        return DigestUtil.sha256Hex(token).substring(0, 12);
    }

    private String hash(String token, String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                (token + ":" + value.trim().toUpperCase()).getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash captcha answer", e);
        }
    }

    // ============ 内部: 图片渲染 ============

    private BufferedImage renderImage(String code, int level) {
        int width = code.length() * 28 + 20;
        int height = 50;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        // 背景
        g.setColor(level >= 3 ? new Color(220, 220, 220) : Color.WHITE);
        g.fillRect(0, 0, width, height);

        // 干扰线(L1 弱, L2/L3 强)
        int lineCount = level == 1 ? 5 : (level == 2 ? 10 : 18);
        for (int i = 0; i < lineCount; i++) {
            g.setColor(randomGray(200, 255));
            int x1 = RandomUtil.randomInt(0, width);
            int y1 = RandomUtil.randomInt(0, height);
            int x2 = RandomUtil.randomInt(0, width);
            int y2 = RandomUtil.randomInt(0, height);
            g.drawLine(x1, y1, x2, y2);
        }

        // L3 加背景噪点
        if (level >= 3) {
            for (int i = 0; i < 50; i++) {
                img.setRGB(RandomUtil.randomInt(0, width), RandomUtil.randomInt(0, height),
                    randomGray(150, 200).getRGB());
            }
        }

        // 字符(每个字符独立位置 + 旋转 + 字号变化)
        g.setFont(new Font("Arial", Font.BOLD, level >= 3 ? 32 : 30));
        for (int i = 0; i < code.length(); i++) {
            // 仿射变换: 平移 + 旋转
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(15 + i * 28, 35 + (i % 2 == 0 ? -3 : 3));
            double angle = level >= 3
                ? Math.toRadians(RandomUtil.randomInt(-35, 35))
                : Math.toRadians(RandomUtil.randomInt(-15, 15));
            g2.rotate(angle);
            g2.setColor(randomGray(20, 130));
            g2.drawString(String.valueOf(code.charAt(i)), 0, 0);
            g2.dispose();
        }

        // L3 再加一层干扰曲线
        if (level >= 3) {
            g.setColor(new Color(120, 120, 120, 80));
            g.setStroke(new BasicStroke(1.5f));
            for (int i = 0; i < 3; i++) {
                int x = RandomUtil.randomInt(0, width);
                g.drawArc(x, 0, 60, height, 0, 180);
            }
        }
        g.dispose();
        return img;
    }

    /** 生成指定灰度范围的随机颜色(用于干扰线和字符, 灰度避免和主字符混淆)。 */
    private Color randomGray(int min, int max) {
        int v = RandomUtil.randomInt(min, max + 1);
        return new Color(v, v, v);
    }

    private String toBase64(BufferedImage img) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            log.error("[Captcha] 图片转 base64 失败", e);
            return null;
        }
    }

    private void writeLog(String token, int level, PassBinding binding, String failReason) {
        try {
            DcsCaptchaLog entry = new DcsCaptchaLog();
            entry.setTenantId(binding.tenantId());
            entry.setToken(token);
            entry.setLevel((short) level);
            entry.setUserId(binding.userId());
            entry.setIp(binding.ip());
            entry.setScene(binding.scene());
            entry.setAction(binding.action());
            entry.setVerified("0");
            entry.setFailReason(failReason);
            entry.setCreatedTime(new Date());
            captchaLogMapper.insert(entry);
        } catch (Exception ignore) {
            // 日志失败不影响验证码流程
        }
    }

    private void updateLog(String token, boolean success, String failReason) {
        try {
            // 简化: 直接 selectOne + updateById
            DcsCaptchaLog existing = captchaLogMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsCaptchaLog>()
                .eq(DcsCaptchaLog::getToken, token)
                .orderByDesc(DcsCaptchaLog::getCreatedTime)
                .last("LIMIT 1"));
            if (existing != null) {
                DcsCaptchaLog patch = new DcsCaptchaLog();
                patch.setLogId(existing.getLogId());
                patch.setVerified(success ? "1" : "0");
                patch.setFailReason(failReason);
                patch.setVerifiedTime(new Date());
                captchaLogMapper.updateById(patch);
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * 验证码生成结果。
     */
    public record CaptchaResult(
        String token,
        String imageBase64,  // data:image/png;base64,...
        int level,
        boolean needOtp,     // L4 返回 true
        String deliveryHint  // L4 返回脱敏邮箱提示
    ) {}

    /** 验证码校验结果(verified=是否通过; needOtp=L4 图片码已过等待 OTP; captchaPassToken=通过后签发的一次性通行证)。 */
    public record VerificationResult(
        boolean verified,
        boolean needOtp,
        String captchaPassToken
    ) {
        public static VerificationResult rejected() {
            return new VerificationResult(false, false, null);
        }

        public static VerificationResult waitingForOtp() {
            return new VerificationResult(true, true, null);
        }

        public static VerificationResult passed(String passToken) {
            return new VerificationResult(true, false, passToken);
        }
    }

    /** Redis 持久化的验证码挑战状态(绑定信息 + 答案/OTP 哈希 + 失败次数), 校验通过后原子删除。 */
    public record CaptchaChallenge(
        String tenantId,
        Long userId,
        String ip,
        String scene,
        String action,
        int level,
        String answerHash,
        String otpHash,
        int failedAttempts
    ) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        public boolean matches(PassBinding binding) {
            return binding != null
                && java.util.Objects.equals(tenantId, binding.tenantId())
                && java.util.Objects.equals(userId, binding.userId())
                && java.util.Objects.equals(ip, binding.ip())
                && java.util.Objects.equals(scene, binding.scene())
                && java.util.Objects.equals(action, binding.action());
        }

        public CaptchaChallenge withAnswerVerified() {
            return new CaptchaChallenge(
                tenantId, userId, ip, scene, action, level, null, otpHash, failedAttempts
            );
        }

        public CaptchaChallenge withFailedAttempts(int attempts) {
            return new CaptchaChallenge(
                tenantId, userId, ip, scene, action, level, answerHash, otpHash, attempts
            );
        }
    }
}
