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
@TableName("ai_credit_ledger")
public class AiCreditLedger implements Serializable {

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

    /**
     * 额度三级管控批（Q17=1）：<b>快照</b>——结算时用的模型名。
     * 改倍率 / 换模型后旧流水这三个数一个字不动（板上验收：两笔都能解释）。
     * 历史行为 NULL，不追填（当时的倍率已无从考证，编一个数就是假账）。
     */
    private String modelName;

    /** 快照：结算时用的倍率（平台系数 × 手工系数）；NULL=历史行 */
    private java.math.BigDecimal creditMultiplier;

    /** 快照：结算时一轮的官方成本（元）；NULL=历史行 */
    private java.math.BigDecimal unitPrice;

    /** 消耗人昵称（业务批8 Q6 双视角流水：服务侧批量回填，非表列） */
    @TableField(exist = false)
    private String operatorName;
}
