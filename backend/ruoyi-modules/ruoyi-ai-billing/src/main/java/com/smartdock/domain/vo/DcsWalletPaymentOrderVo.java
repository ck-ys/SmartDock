package com.smartdock.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.domain.DcsWalletPaymentOrder;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 钱包充值订单视图对象。
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = DcsWalletPaymentOrder.class)
public class DcsWalletPaymentOrderVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 订单ID */
    private Long orderId;
    private String tenantId;
    /** 充值目标钱包ID */
    private Long walletId;
    /** 订单号(业务唯一) */
    private String orderNo;
    /** 支付渠道: MANUAL=手动, WECHAT_PAY=微信, ALIPAY=支付宝, MOCK=模拟 */
    private String channel;
    /** 充值金额(单位随币种, 通用为元) */
    private BigDecimal amount;
    /** 币种 */
    private String currency;
    /** 订单状态: PENDING=待支付, PAID=已支付, CLOSED=已关闭, REFUNDED=已退款 */
    private String status;
    /** 支付完成时间 */
    private Date payTime;
    /** 支付渠道回调载荷 JSON */
    private String notifyPayload;
    /** 幂等键(防重复入账) */
    private String idempotencyKey;
    /** 操作人ID */
    private Long operatorId;
    private String remark;
    private Date createTime;
    private Date updateTime;
}
