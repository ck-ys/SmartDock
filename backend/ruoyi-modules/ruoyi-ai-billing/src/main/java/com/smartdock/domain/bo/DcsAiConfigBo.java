package com.smartdock.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import com.smartdock.domain.DcsAiConfig;

/**
 * DCS AI 模型配置业务对象。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = DcsAiConfig.class, reverseConvertGenerate = false)
public class DcsAiConfigBo extends BaseEntity {

    /** 配置ID(编辑必填) */
    @NotNull(message = "配置ID不能为空", groups = {EditGroup.class})
    private Long configId;

    /** 开通 AI 的租户编号(新增/编辑必填) */
    @NotBlank(message = "租户编号不能为空", groups = {AddGroup.class, EditGroup.class})
    private String tenantId;

    /** 模型厂商(见 DcsAiProvider 枚举: OPENAI/DEEPSEEK/QWEN/ZHIPU/KIMI 等)(新增/编辑必填) */
    @NotBlank(message = "模型厂商不能为空", groups = {AddGroup.class, EditGroup.class})
    private String provider;

    /** 配置用途: CHAT=AI 调用, EMBEDDING=向量化; 不传默认 CHAT(兼容旧前端) */
    private String useType;

    /** OpenAI 兼容 API 的 base URL(新增/编辑必填) */
    @NotBlank(message = "接口地址不能为空", groups = {AddGroup.class, EditGroup.class})
    private String baseUrl;

    /** API Key(批B 多模型改造:是否必填由服务端按「该厂商是否已有接入行」判定——同厂加模型免填,首次配置必填;真钥匙保存时路由到接入层) */
    private String apiKey;

    /** 模型名称(新增/编辑必填) */
    @NotBlank(message = "模型名称不能为空", groups = {AddGroup.class, EditGroup.class})
    private String modelName;

    /** 模型上下文窗口(仅作为管理配置元数据) */
    private Integer contextWindow;

    /** 单次回答最大输出 tokens（批C C1 表单化；256..131072，空=模型/框架默认） */
    @Min(value = 256, message = "最大输出不能低于 256 tokens", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 131072, message = "最大输出不能超过 131072 tokens", groups = {AddGroup.class, EditGroup.class})
    private Integer maxTokens;

    /** 批C 回修（dsflash P2-2）：显式清空信号——null 双关「未提交」与「清空」会让未来的部分提交
     *  调用方静默清列；清空=前端把本位置 true，后端只在 true 时落 NULL */
    private Boolean clearMaxTokens;

    /** 采样温度 */
    private Double temperature;

    /** 调用超时秒数 */
    private Integer timeoutSeconds;

    /** 默认星标(只读展示用;写路径统一走 activate 换星,insert/update 一律剥离防并发撞唯一星索引) */
    private String isDefault;

    /** 是否启用 0=停用 1=启用 */
    private String enabled;

    /** 每次 AI 调用单价(元), 默认 0.01 */
    private java.math.BigDecimal unitPricePerCall;

    /** 是否启用余额查询 */
    private String balanceQueryEnabled;

    /** 计费模式: OFFICIAL_TOKEN/FIXED_PER_CALL/OFFICIAL_PLUS_FEE(业务批4 Q4:官方价+每次服务费) */
    private String billingMode;

    /** 服务技术费率百分比,如 10.00 表示加收 10% */
    private java.math.BigDecimal serviceFeeRate;

    /**
     * 业务反馈批1015b Q13：积分消耗倍率（1 积分=1 分钱基准 × 此倍率；NULL/0 视同 1）。
     * 种子口径：免费×1 / flash 档×2 / 中档×2~3 / 旗舰×4~6——此前前后端零编辑入口只能改库，
     * 本批补模型表单字段。
     */
    private java.math.BigDecimal creditMultiplier;

    /** 思考模式强度(空=关闭/low/medium/high/max) */
    private String reasoningEffort;

    private String remark;
}
