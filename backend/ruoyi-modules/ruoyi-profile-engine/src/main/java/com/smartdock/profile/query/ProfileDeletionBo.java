package com.smartdock.profile.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 主体级删除请求体(批11 Q2): confirmText 必须回显 subjectId 才放行执行。
 *
 * @author smartdock
 */
@Data
public class ProfileDeletionBo {

    @NotBlank(message = "source 不能为空")
    private String source;

    @NotBlank(message = "subjectType 不能为空")
    private String subjectType;

    @NotBlank(message = "subjectId 不能为空")
    private String subjectId;

    @NotBlank(message = "确认文本不能为空, 须回显主体 ID")
    private String confirmText;
}
