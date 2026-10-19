package com.smartdock.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.domain.DcsAiModelPrice;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * AI 模型价格配置视图对象。
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = DcsAiModelPrice.class)
public class DcsAiModelPriceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 价格ID */
    private Long priceId;
    private String tenantId;
    /** 模型厂商(见 DcsAiProvider) */
    private String provider;
    /** 模型名称 */
    private String modelName;
    /** 币种 */
    private String currency;
    /** 输入 token 单价(每 100 万 token, 单位随币种) */
    private BigDecimal inputPricePerMillion;
    /** 缓存命中输入 token 单价(每 100 万 token, 为空按输入单价计) */
    private BigDecimal cachedInputPricePerMillion;
    /** 输出 token 单价(每 100 万 token) */
    private BigDecimal outputPricePerMillion;
    /** 价格来源地址 */
    private String sourceUrl;
    /** 是否启用("1"=启用, "0"=停用) */
    private String enabled;
    private String remark;
    /** 定价区域: CN=中国, GLOBAL=全球, US=美国, SG=新加坡 */
    private String pricingRegion;
    /** 定价分层: DEFAULT=默认, 0_32K, 32K_128K, PRIORITY=优先级 */
    private String pricingTierCode;
    /** 该定价适用的最小输入 token 数 */
    private Integer inputTokenMin;
    /** 该定价适用的最大输入 token 数 */
    private Integer inputTokenMax;
    /** 计费公式: INPUT_OUTPUT_PER_MILLION / FLAT_PER_CALL / TIERED_INPUT_OUTPUT / CUSTOM_JSON */
    private String billingFormula;
    /** 原始结构化价格元数据 JSON(复杂计费规则厂商用) */
    private String pricingSchemaJson;
    /** 价格来源的更新时间 */
    private Date sourceUpdatedAt;
    private Date createTime;
    private Date updateTime;
}
