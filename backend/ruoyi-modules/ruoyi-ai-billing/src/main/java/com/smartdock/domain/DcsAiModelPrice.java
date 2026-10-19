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
 * AI 模型官方计费价格配置。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_ai_model_price")
public class DcsAiModelPrice extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 价格ID */
    @TableId(value = "price_id")
    private Long priceId;

    /** 租户ID */
    private String tenantId;

    /** 模型厂商 */
    private String provider;

    /** 模型名称 */
    private String modelName;

    /** 币种 */
    private String currency;

    /** 输入 token 单价(每 1M token)。 */
    private BigDecimal inputPricePerMillion;

    /** 缓存输入 token 单价(每 1M token)。为空时按输入单价计。 */
    private BigDecimal cachedInputPricePerMillion;

    /** 输出 token 单价(每 1M token)。 */
    private BigDecimal outputPricePerMillion;

    /** 价格来源地址 */
    private String sourceUrl;

    /** 是否启用 */
    private String enabled;

    /** 备注 */
    private String remark;

    // ========== 扩展字段: 官方计费元数据 ==========

    /** 定价区域: CN / GLOBAL / US / SG */
    private String pricingRegion;

    /** 定价分层: DEFAULT / 0_32K / 32K_128K / PRIORITY */
    private String pricingTierCode;

    /** 最小输入token */
    private Integer inputTokenMin;

    /** 最大输入token */
    private Integer inputTokenMax;

    /** 计费公式: INPUT_OUTPUT_PER_MILLION / FLAT_PER_CALL / TIERED_INPUT_OUTPUT / CUSTOM_JSON */
    private String billingFormula;

    /** 原始结构化价格元数据(复杂计费规则厂商用) */
    private String pricingSchemaJson;

    /** 来源更新时间 */
    private Date sourceUpdatedAt;

    /** 删除标志 */
    @TableLogic
    private String delFlag;
}
