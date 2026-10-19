package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 租户 AI 消费方式开关（2026-10-14）：租户显式二选一——
 * WALLET=钱包按量（默认，无行视同 WALLET）/ CREDIT=积分订阅×加油包。
 *
 * <p>联动口径：订阅成功自动切 CREDIT；订阅到期停用后首个读取请求自动回写 WALLET
 * （见 {@code DcsAiCreditServiceImpl#consumeMode}）；切 CREDIT 前置校验有生效订阅。
 * 跨租户管理表（禁租户拦截），谓词显式 tenantId。</p>
 */
@Data
@TableName("dcs_tenant_billing_mode")
public class DcsTenantBillingMode implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "tenant_id")
    private String tenantId;

    /** WALLET=钱包按量 / CREDIT=积分订阅 */
    private String consumeMode;

    private Long updateBy;

    private Date updateTime;
}
