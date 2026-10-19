package com.smartdock.domain.bo;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 套餐档位编辑 BO（业务批8 Q4：管理端任何参数可改）。id 空=新增，非空=全字段更新。
 * planCode 唯一（SUBSCRIBE 档被订阅行引用，改 code 会让存量订阅断链——编辑时 code 只读由前端保证）。
 *
 * @author smartdock
 */
@Data
public class DcsAiCreditPlanBo {

    /** 空=新增 */
    private Long id;

    @NotBlank(message = "套餐编码不能为空")
    @Size(max = 40, message = "套餐编码不超过 40 字符")
    private String planCode;

    @NotBlank(message = "套餐类型不能为空")
    @Pattern(regexp = "SUBSCRIBE|BOOST", message = "套餐类型只能是 SUBSCRIBE 或 BOOST")
    private String kind;

    @NotBlank(message = "套餐名称不能为空")
    @Size(max = 60, message = "套餐名称不超过 60 字符")
    private String planName;

    @NotNull(message = "售价不能为空")
    @DecimalMin(value = "0", message = "售价不能为负")
    private BigDecimal priceYuan;

    @NotNull(message = "积分量不能为空")
    @Min(value = 0, message = "积分量不能为负")
    @Max(value = 10000000, message = "积分量超出上限")
    private Integer weeklyCredits;

    @Min(value = 0, message = "窗口上限不能为负")
    private Integer windowCredits;

    @Min(value = 1, message = "窗口时长至少 1 小时")
    private Integer windowHours;

    @Min(value = 0, message = "订阅时长不能为负")
    private Integer durationWeeks;

    /**
     * 业务反馈批1015b Q12：整周期总积分额度（NULL=按 每周×周数 推导；填了以填写值为准）。
     * 先软后硬口径：第一版只做展示+快用完预警，不改变"按周池扣费"逻辑（用满总额会不会断，
     * 用户实测后再拍）。
     */
    private Long totalCredits;

    @Min(value = 0, message = "联网次数档位不能为负")
    private Integer websearchLimitPerUserDaily;

    @Size(max = 300, message = "描述不超过 300 字符")
    private String description;
}
