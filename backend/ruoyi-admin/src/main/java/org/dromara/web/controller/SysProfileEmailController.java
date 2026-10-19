package org.dromara.web.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.dromara.web.service.SysEmailBindService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人信息·邮箱自助绑定（批B 2026-09-26 拍板 Q2-B）
 *
 * <p>放在 admin 模块而非 system 的 SysProfileController：服务依赖
 * common-mail（发码）与 sa-token 登录态，与批4 SysPwdResetService 同款落位；
 * 路径挂在 /system/user/profile/email/* 下与个人信息页语义对齐。</p>
 *
 * @author smartdock
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/user/profile/email")
public class SysProfileEmailController extends BaseController {

    private final SysEmailBindService emailBindService;

    /**
     * 发送邮箱绑定验证码（登录态，验证新邮箱所有权，60 秒限发）
     *
     * @return 打码后的目标邮箱
     */
    @RepeatSubmit
    @GetMapping("/code")
    public R<String> code(@Email(message = "邮箱格式不正确") @RequestParam String email) {
        return R.ok("验证码已发送", emailBindService.sendCode(email.trim()));
    }

    /**
     * 绑定/更换邮箱（验证码单次消费 + 唯一性复验）
     *
     * @return 打码后的新邮箱
     */
    @RepeatSubmit
    @Log(title = "个人信息", businessType = BusinessType.UPDATE)
    @PostMapping("/bind")
    public R<String> bind(@Validated @RequestBody EmailBindBody body) {
        return R.ok("绑定成功", emailBindService.bind(body.email().trim(), body.code().trim()));
    }

    /**
     * 绑定请求体（email 补 @NotBlank——深挖审核 Q8①：@Email 对 null 放行，
     * 不带 email 的请求体会穿到 body.email().trim() 空指针 500）
     */
    public record EmailBindBody(@NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") String email,
                                @NotBlank(message = "验证码不能为空") String code) {}
}
