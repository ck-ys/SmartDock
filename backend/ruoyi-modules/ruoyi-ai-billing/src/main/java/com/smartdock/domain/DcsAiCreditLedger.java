package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 积分流水（五需求批 R5）：biz_type=SUBSCRIBE 订阅开单 / BOOST 加油包充值 /
 * DEDUCT 消耗（credits=总量，weekly_credits+tank_credits=来源拆分）/ ADJUST 人工调整。
 * 周池与 5h 窗口用量=本表现算 SUM（唯一真源，无 Redis 漂移）。
 */
@Data
@TableName("dcs_ai_credit_ledger")
public class DcsAiCreditLedger implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    private String tenantId;

    /** SUBSCRIBE / BOOST / DEDUCT / ADJUST */
    private String bizType;

    /** 积分量（恒正数，方向由 biz_type 表达） */
    private Integer credits;

    /** DEDUCT 行：从周池扣的积分 */
    private Integer weeklyCredits;

    /** DEDUCT 行：从加油包余额扣的积分 */
    private Integer tankCredits;

    /** 场景（assist:chat:seqN 等） */
    private String scene;

    /** 幂等键（唯一索引防重放双扣） */
    private String idempotencyKey;

    private String remark;

    private Long createBy;

    private Date createTime;

    /** 消耗人昵称（业务批8 Q6 双视角流水：服务侧批量回填，非表列） */
    @TableField(exist = false)
    private String operatorName;
}
