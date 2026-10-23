package com.smartdock.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import com.smartdock.domain.DcsAiModelPrice;

import java.math.BigDecimal;

/**
 * AI 模型价格配置业务对象。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = DcsAiModelPrice.class, reverseConvertGenerate = false)
public class DcsAiModelPriceBo extends BaseEntity {

    /** 价格ID(编辑必填) */
    @NotNull(message = "价格ID不能为空", groups = {EditGroup.class})
    private Long priceId;

    private String tenantId;

    /** 模型厂商(新增/编辑必填) */
    @NotBlank(message = "模型厂商不能为空", groups = {AddGroup.class, EditGroup.class})
    private String provider;

    /** 模型名称(新增/编辑必填) */
    @NotBlank(message = "模型名称不能为空", groups = {AddGroup.class, EditGroup.class})
    private String modelName;

    /** 币种 */
    private String currency;

    /** 输入 token 单价(每 1M token)(新增/编辑必填) */
    @NotNull(message = "输入单价不能为空", groups = {AddGroup.class, EditGroup.class})
    private BigDecimal inputPricePerMillion;

    /** 缓存输入 token 单价(每 1M token);为空时按输入单价计 */
    private BigDecimal cachedInputPricePerMillion;

    /** 输出 token 单价(每 1M token)(新增/编辑必填) */
    @NotNull(message = "输出单价不能为空", groups = {AddGroup.class, EditGroup.class})
    private BigDecimal outputPricePerMillion;

    /** 价格来源地址 */
    private String sourceUrl;

    /** 是否启用 1=启用 0=停用 */
    private String enabled;

    private String remark;

    /** 定价区域: CN/GLOBAL/US/SG */
    private String pricingRegion;
    /** 定价分层: DEFAULT/0_32K/32K_128K/PRIORITY */
    private String pricingTierCode;
    /** 最小输入 token */
    private Integer inputTokenMin;
    /** 最大输入 token */
    private Integer inputTokenMax;
    /** 计费公式: INPUT_OUTPUT_PER_MILLION/FLAT_PER_CALL/TIERED_INPUT_OUTPUT/CUSTOM_JSON */
    private String billingFormula;
    /** 原始结构化价格元数据(复杂计费规则厂商用) */
    private String pricingSchemaJson;

    /** Q6-A：来源类型 MANUAL / MODELS_DEV / IMPORT（手工新增/编辑默认 MANUAL） */
    private String priceSource;

    /** Q6-A：1=人工价，models.dev 同步不覆盖（手工新增/编辑默认 1） */
    private String manualOverride;
}
