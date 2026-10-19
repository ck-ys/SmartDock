package com.smartdock.risk.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 验证码生成与校验日志(用于审计)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_captcha_log")
public class DcsCaptchaLog extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "log_id")
    private Long logId;

    private String token;

    /** 1/2/3/4 难度等级 */
    private Short level;

    private Long userId;

    private String ip;

    /** LOGIN / SEND_MSG / ... */
    private String scene;

    /** Concrete protected action, for example chat:88. */
    private String action;

    /** 0=未校验/失败, 1=校验成功 */
    private String verified;

    private String failReason;

    private Date createdTime;

    private Date verifiedTime;
}
