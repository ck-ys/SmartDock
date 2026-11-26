package com.smartdock.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 搜索服务出参（额度三级管控批 Q16=1，2026-11-26）。
 *
 * <p><b>为什么不直接回实体</b>：{@code ai_search_service.api_key} 是加密列，
 * 实体序列化会把明文带出去——与钥匙管理页同口径，出参只给掩码。</p>
 *
 * <p><b>余额与资源包是两本账</b>（板上点名的坑）：账户余额（元，自动查）与
 * 资源包次数（估算）可以同时成立，所以分字段回，界面分两列显示，不做换算。</p>
 */
@Data
public class AiSearchServiceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String tenantId;
    /** 搜索厂商（当前只有 BOCHA） */
    private String provider;
    /** 显示名（空=界面按 provider 显示） */
    private String label;
    private String enabled;

    /** 密钥掩码：前 6 + **** + 后 4；太短整体打码。**永不回明文** */
    private String apiKeyMask;
    /** 是否已填过密钥（界面上「未填」与「已填但掩码为空」要分开） */
    private Boolean hasKey;

    private String consoleUrl;

    /** 账户余额（元）——官方 GET /v1/fund/remaining 查回来；查不到时 amount 为 null */
    private BigDecimal balanceAmount;
    private String balanceCurrency;
    private Date balanceCheckedAt;
    /** OK / UNSUPPORTED / ERROR；界面据此决定显示金额 / 「—」/「查不到」 */
    private String balanceStatus;

    /** 资源包总次数（管理员填；NULL=没买包） */
    private Integer packTotal;
    /** 估算：系统累计调用次数（厂商无接口，以厂商后台为准） */
    private Integer packUsed;
    private Date packExpireAt;
}
