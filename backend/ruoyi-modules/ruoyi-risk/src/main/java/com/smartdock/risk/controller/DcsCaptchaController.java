package com.smartdock.risk.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.risk.config.DcsRolloutProperties;
import com.smartdock.risk.service.CaptchaPassService.PassBinding;
import com.smartdock.risk.service.CaptchaService;
import com.smartdock.risk.service.RiskActionService;
import com.smartdock.risk.service.RiskScoreService;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 验证码 Controller。
 *
 * <p><b>核心流程</b>:</p>
 * <ol>
 *   <li>前端调 {@code GET /dcs/captcha/need} 检查当前用户是否需要验证码</li>
 *   <li>需要则调 {@code POST /dcs/captcha/generate} 生成对应难度的验证码</li>
 *   <li>用户输入后调 {@code POST /dcs/captcha/verify} 校验</li>
 *   <li>L4 场景额外调 {@code POST /dcs/captcha/verify-otp} 校验 OTP</li>
 * </ol>
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/dcs/captcha")
@RequiredArgsConstructor
public class DcsCaptchaController {

    /** 验证码生成/校验业务服务 */
    private final CaptchaService captchaService;
    /** 风险评分业务服务（按等级决定是否需要验证码） */
    private final RiskScoreService riskScoreService;
    /** 风险处置业务服务（评估同步处置） */
    private final RiskActionService riskActionService;

    @Autowired(required = false)
    private DcsRolloutProperties rolloutProperties;

    /**
     * 检查当前用户是否需要验证码(基于风控等级)。
     *
     * @param scene 场景 LOGIN/SEND_MSG
     * @return 是否需要验证码及当前风险等级信息
     */
    @GetMapping("/need")
    public R<Map<String, Object>> need(@RequestParam(defaultValue = "SEND_MSG") String scene) {
        Long userId = LoginHelper.getUserId();
        String ip = ServletUtils.getClientIP();
        RiskScoreService.RiskAssessment assessment = riskScoreService.assess(userId, ip);
        riskActionService.syncAssessment(LoginHelper.getTenantId(), assessment, "风险验证入口评估");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("needCaptcha", assessment.needCaptcha() && isChallengeEnabled(assessment.level()));
        data.put("level", assessment.level());
        data.put("totalScore", assessment.totalScore());
        return R.ok(data);
    }

    /**
     * 生成验证码(按当前风控等级自动选难度)。
     *
     * @param request 生成验证码请求（scene + action）
     * @return 生成的验证码结果
     */
    @Log(title = "DCS验证码-生成", businessType = BusinessType.OTHER,
        isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/generate")
    public R<CaptchaService.CaptchaResult> generate(@Valid @RequestBody GenerateRequest request) {
        Long userId = LoginHelper.getUserId();
        String ip = ServletUtils.getClientIP();
        RiskScoreService.RiskAssessment assessment = riskScoreService.assess(userId, ip);
        riskActionService.syncAssessment(LoginHelper.getTenantId(), assessment, "风险验证码生成评估");
        int level = isChallengeEnabled(assessment.level()) ? Math.max(1, assessment.level()) : 1;
        CaptchaService.CaptchaResult result = captchaService.generate(level, binding(request.scene(), request.action()));
        return R.ok(result);
    }

    /**
     * 校验验证码。
     *
     * @param request 校验验证码请求（token + answer + scene + action）
     * @return 验证码校验结果
     */
    @Log(title = "DCS验证码-校验", businessType = BusinessType.OTHER,
        isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping("/verify")
    public R<CaptchaService.VerificationResult> verify(@Valid @RequestBody VerifyRequest request) {
        CaptchaService.VerificationResult result = captchaService.verify(
            request.token(),
            request.answer(),
            binding(request.scene(), request.action())
        );
        return R.ok(result);
    }

    /**
     * 校验 L4 OTP。
     *
     * @param request OTP校验请求（token + otpCode + scene + action）
     * @return OTP校验结果
     */
    @PostMapping("/verify-otp")
    public R<CaptchaService.VerificationResult> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        CaptchaService.VerificationResult result = captchaService.verifyOtp(
            request.token(),
            request.otpCode(),
            binding(request.scene(), request.action())
        );
        return R.ok(result);
    }

    private PassBinding binding(String scene, String action) {
        return new PassBinding(
            LoginHelper.getTenantId(),
            LoginHelper.getUserId(),
            ServletUtils.getClientIP(),
            scene,
            action
        );
    }

    private boolean isChallengeEnabled(int level) {
        return rolloutProperties == null || rolloutProperties.isChallengeEnabled(level);
    }

    public record GenerateRequest(
        @NotBlank String scene,
        @NotBlank String action
    ) {
    }

    public record VerifyRequest(
        @NotBlank String token,
        @NotBlank String answer,
        @NotBlank String scene,
        @NotBlank String action
    ) {
    }

    public record VerifyOtpRequest(
        @NotBlank String token,
        @NotBlank String otpCode,
        @NotBlank String scene,
        @NotBlank String action
    ) {
    }
}
