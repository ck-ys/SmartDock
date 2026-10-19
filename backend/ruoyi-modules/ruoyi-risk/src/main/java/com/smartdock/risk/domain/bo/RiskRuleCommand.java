package com.smartdock.risk.domain.bo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 风控规则配置命令 BO，承载后台管理 UI 提交的可编辑规则字段。
 * <p>
 * 一条风控规则描述: "在 {@code windowMinutes} 分钟的滑动窗口内，针对事件类型
 * {@code eventType} 累计积分达到 {@code thresholdL1~L4} 时升级到对应风险等级"。
 * <ul>
 *   <li>{@code ruleScope}: 作用域，GLOBAL 全局生效 / TYPE 按任务类型生效</li>
 *   <li>{@code weight}: 单次事件积分权重 (0-20)</li>
 *   <li>{@code thresholdL1~L4}: 升级到 L1/L2/L3/L4 风险等级的积分阈值 (1-100)</li>
 *   <li>{@code enabled}: 是否启用，1 启用 / 0 停用</li>
 * </ul>
 *
 * @author smartdock
 */
public record RiskRuleCommand(
    /** 规则 ID (新增时为空，更新时必填) */
    Long ruleId,
    /** 事件类型代码 (大写字母开头，仅含大写字母/数字/下划线，长度 1-40) */
    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,39}") String eventType,
    /** 规则作用域: GLOBAL 全局 / TYPE 按任务类型 */
    String ruleScope,
    /** 单次事件积分权重 (0-20) */
    @NotNull @Min(0) @Max(20) Integer weight,
    /** 是否启用，"1" 启用 / "0" 停用 */
    @NotBlank @Pattern(regexp = "[01]") String enabled,
    /** 升级到 L1 风险等级的积分阈值 (1-100) */
    @NotNull @Min(1) @Max(100) Integer thresholdL1,
    /** 升级到 L2 风险等级的积分阈值 (1-100) */
    @NotNull @Min(1) @Max(100) Integer thresholdL2,
    /** 升级到 L3 风险等级的积分阈值 (1-100，触发增强验证) */
    @NotNull @Min(1) @Max(100) Integer thresholdL3,
    /** 升级到 L4 风险等级的积分阈值 (1-100，触发临时冻结) */
    @NotNull @Min(1) @Max(100) Integer thresholdL4,
    /** 积分累计的滑动窗口时长，单位分钟 (1-10080，即最长 7 天) */
    @NotNull @Min(1) @Max(10080) Integer windowMinutes,
    /** 规则描述 (可选，最长 200 字) */
    @Size(max = 200) String description
) {
}
