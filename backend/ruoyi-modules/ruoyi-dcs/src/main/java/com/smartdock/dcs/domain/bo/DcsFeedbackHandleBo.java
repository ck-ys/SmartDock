package com.smartdock.dcs.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 反馈处理入参（N3 2026-10-23）。
 *
 * @author smartdock
 */
@Data
public class DcsFeedbackHandleBo {

    @NotNull(message = "反馈 ID 不能为空")
    private Long feedbackId;

    /** 只允许处理中 / 已解决 / 已关闭；不允许改回待处理 */
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "PROCESSING|RESOLVED|CLOSED", message = "状态不合法")
    private String status;

    @NotBlank(message = "处理意见不能为空")
    @Size(min = 5, max = 1000, message = "处理意见需在 5~1000 字之间")
    private String handleNote;
}
