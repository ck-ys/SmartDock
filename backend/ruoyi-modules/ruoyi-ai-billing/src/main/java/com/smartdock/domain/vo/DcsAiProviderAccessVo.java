package com.smartdock.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.domain.DcsAiProviderAccess;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 厂商接入视图对象（批B，2026-10-09）。永远只出掩码，不出明文 API Key。
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = DcsAiProviderAccess.class)
public class DcsAiProviderAccessVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 模型厂商 */
    private String provider;

    /** 厂商显示名（服务端填充，前端免字典映射） */
    private String providerName;

    /** OpenAI 兼容接口地址 */
    private String baseUrl;

    /** API Key 掩码串（已配置状态展示） */
    private String apiKeyMask;

    /** 厂商控制台直达链接 */
    private String consoleUrl;

    /** 备注 */
    private String remark;

    /** 本次钥匙变更已生效的模型行数（仅更新接口回填，查询时为 null） */
    private Integer affectedModelCount;

    /** 钥匙标签（AI 缺口批 Q8） */
    private String label;

    /** 钥匙启停（"1"/"0"；null 视同启用） */
    private String enabled;

    /** 是否主钥匙（enabled 按 id 升序第一行；探测类端点与配置卡余额的取钥口径） */
    private Boolean primary;

    /** 厂商侧账户余额快照（null=无快照；UNSUPPORTED 时 null 显示 "--"） */
    private java.math.BigDecimal balanceAmount;

    /** 余额币种 */
    private String balanceCurrency;

    /** 余额快照时间 */
    private Date balanceCheckedAt;

    /** 余额探测状态（OK/UNSUPPORTED/FAILED/null=未探测） */
    private String balanceStatus;

    private Date createTime;

    private Date updateTime;
}
