package com.smartdock.risk.domain.bo;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 风控白名单更新参数（轻量 BO，区别于全量 {@link RiskWhitelistCommand}）。
 *
 * <p>设计意图：白名单的 {@code listType/value} 字段在创建后语义上不变（IP 还是 IP、值还是那个值），
 * 管理员日常维护主要改 {@code reason}（原因说明）和 {@code enabled}（启停）。
 * 如果用全量 record 接 update，会强制前端回填 listType/value 并触发 NotBlank 校验，体验差。
 * 因此新建此轻量 BO，只接收可变字段。
 *
 * @param reason  修改后的原因说明，可选（最长 200 字符）
 * @param enabled 启用状态：{@code "1"} 启用 / {@code "0"} 禁用，可选
 *
 * @author smartdock
 */
public record RiskWhitelistUpdateCommand(
    @Size(max = 200) String reason,
    @Pattern(regexp = "[01]") String enabled
) {
}
