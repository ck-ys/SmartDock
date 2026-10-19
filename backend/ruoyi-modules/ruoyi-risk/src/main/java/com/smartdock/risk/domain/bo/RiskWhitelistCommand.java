package com.smartdock.risk.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 风控白名单命令 BO，承载后台管理 UI 提交的白名单条目字段。
 * <p>
 * 白名单中的用户或 IP 将跳过风控评分与处置逻辑。一条白名单条目由以下要素构成:
 * <ul>
 *   <li>{@code listType}: 名单维度，IP 按 IP 维度 / USER 按用户维度</li>
 *   <li>{@code value}: 名单值 (脱敏后的 IP 或用户标识，最长 200 字)</li>
 *   <li>{@code reason}: 加白原因 (可选，最长 200 字，建议必填以备审计)</li>
 *   <li>{@code enabled}: 是否启用，"1" 启用 / "0" 停用</li>
 * </ul>
 *
 * @author smartdock
 */
public record RiskWhitelistCommand(
    /** 名单维度: IP 按 IP 维度 / USER 按用户维度 */
    @NotBlank @Pattern(regexp = "IP|USER") String listType,
    /** 名单值 (脱敏后的 IP 或用户标识，最长 200 字) */
    @NotBlank @Size(max = 200) String value,
    /** 加白原因 (可选，最长 200 字，建议必填以备审计) */
    @Size(max = 200) String reason,
    /** 是否启用，"1" 启用 / "0" 停用 */
    @Pattern(regexp = "[01]") String enabled
) {
}
