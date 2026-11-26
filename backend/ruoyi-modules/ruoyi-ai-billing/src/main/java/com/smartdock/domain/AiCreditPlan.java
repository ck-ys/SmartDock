package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * AI 积分套餐档位（五需求批 R5，2026-10-13）：kind=SUBSCRIBE 订阅档（周池+窗口+时长）/
 * BOOST 加油包（一次性余额）。跨租户管理表（种子数据，禁租户拦截）。
 */
@Data
@TableName("ai_credit_plan")
public class AiCreditPlan implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    /** LITE/PRO/MAX/BOOST99 */
    private String planCode;

    /** SUBSCRIBE=订阅档 / BOOST=加油包 */
    private String kind;

    private String planName;

    /** 售价（元） */
    private BigDecimal priceYuan;

    /** 每周积分池（SUBSCRIBE；BOOST 恒 0） */
    private Integer weeklyCredits;

    /** 滚动窗口积分上限（防刷） */
    private Integer windowCredits;

    /** 窗口时长（小时，缺省 5） */
    private Integer windowHours;

    /** 订阅时长（周） */
    private Integer durationWeeks;

    /**
     * 业务反馈批1015b Q12：整周期总积分额度（NULL=按 每周×周数 推导；填了以填写值为准）。
     * 先软后硬口径：第一版只做展示+快用完预警，不改变"按周池扣费"逻辑（用满总额会不会断，
     * 用户实测后再拍）。
     */
    private Long totalCredits;

    /** 业务批7 Q1：订阅期每人每天联网搜索次数档位（0/NULL=沿用租户默认；种子 LITE/PRO/MAX=50/150/400） */
    private Integer websearchLimitPerUserDaily;

    /**
     * 额度三级管控批（2026-11-26 / Q4=3 + Q8=3 + Q18=1）：套餐建议值——该档位下每人每天建议积分上限。
     * NULL/0=没给建议，算上限时落到平台兜底数。超管在套餐页填，企业可改（企业默认列）。
     */
    private Integer suggestedDailyCreditPerUser;

    private String description;

    /** 1=在售 0=下架 */
    private String enabled;
}
