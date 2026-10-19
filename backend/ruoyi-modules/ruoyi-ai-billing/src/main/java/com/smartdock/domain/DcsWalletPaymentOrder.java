package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 钱包充值订单。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_wallet_payment_order")
public class DcsWalletPaymentOrder extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单ID */
    @TableId(value = "order_id")
    private Long orderId;

    /** 租户ID */
    private String tenantId;

    /** 钱包ID */
    private Long walletId;

    /** 订单号 */
    private String orderNo;

    /** MANUAL / WECHAT_PAY / ALIPAY / MOCK */
    private String channel;

    /** 金额 */
    private BigDecimal amount;

    /** 币种 */
    private String currency;

    /** PENDING / PAID / CLOSED / REFUNDED */
    private String status;

    /** 支付时间 */
    private Date payTime;

    /** 回调载荷 */
    private String notifyPayload;

    /** 幂等键 */
    private String idempotencyKey;

    /** 操作人ID */
    private Long operatorId;

    /** 备注 */
    private String remark;

    /** 删除标志 */
    @TableLogic
    private String delFlag;
}
