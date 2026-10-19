package org.dromara.web.domain.vo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 忘记密码·邮箱验证码重置 请求体（批4 2026-09-26 拍板 8A）
 *
 * <p>tenantId 由前端先识别（复用批2 /auth/tenant/listByLoginId）后传入；
 * 明文提交（与 login/register 放开 @ApiEncrypt 同款先例）。</p>
 *
 * @author smartdock
 */
@Data
public class PwdResetBody implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标租户（识别接口选定） */
    @NotBlank(message = "缺少单位信息，请重新识别账号")
    private String tenantId;

    /** 手机号或账号 */
    @NotBlank(message = "请输入手机号或账号")
    private String loginId;

    /** 邮箱验证码 */
    @NotBlank(message = "请输入邮箱验证码")
    private String code;

    /** 新密码（5-20 位，格式校验在 Service 内与注册同款） */
    @NotBlank(message = "请输入新密码")
    private String password;
}
