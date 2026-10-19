package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 积分订阅（五需求批 R5）：每租户至多一条 status=1 且 end_time 在未来的生效行；
 * 到期由闸现算判定（end_time 过期即失效），无需定时 job 翻状态。
 */
@Data
@TableName("dcs_ai_credit_subscription")
public class DcsAiCreditSubscription implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private String tenantId;

    private Long planId;

    private String planCode;

    private String planName;

    private Date startTime;

    private Date endTime;

    /** 1=生效（叠加 end_time 判定；历史行翻 0） */
    private String status;

    private Long createBy;

    private Date createTime;
}
