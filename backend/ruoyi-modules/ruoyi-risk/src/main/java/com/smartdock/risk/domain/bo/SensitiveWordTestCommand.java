package com.smartdock.risk.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 敏感词命中自测命令 BO(2026-08-24, 词库管理页"命中测试")。
 *
 * @param text  待测文本(只读匹配, 不产生风险事件不记分)
 * @param scene 生效场景(可选, CHAT/FORM/FILE; 空=全部词, 2026-09-22 场景化)
 * @author smartdock
 */
public record SensitiveWordTestCommand(
    @NotBlank(message = "测试文本不能为空")
    @Size(max = 2000, message = "测试文本不能超过 2000 字")
    String text,
    String scene
) {
}
