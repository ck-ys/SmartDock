package com.smartdock.dcs.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 反馈提交 / 查询条件（N3 2026-10-23）。
 *
 * @author smartdock
 */
@Data
public class DcsFeedbackBo {

    @NotBlank(message = "标题不能为空", groups = Submit.class)
    @Size(min = 4, max = 200, message = "标题长度需在 4~200 字之间", groups = Submit.class)
    private String title;

    @NotBlank(message = "描述不能为空", groups = Submit.class)
    @Size(min = 10, max = 2000, message = "描述长度需在 10~2000 字之间", groups = Submit.class)
    private String content;

    @NotBlank(message = "反馈类型不能为空", groups = Submit.class)
    @Pattern(regexp = "BUG|EXPERIENCE|SUGGEST|OTHER", message = "反馈类型不合法", groups = Submit.class)
    private String feedbackType;

    @Size(max = 500, message = "截图数量超出限制", groups = Submit.class)
    private String ossIds;

    @Size(max = 100, message = "联系方式最长 100 字", groups = Submit.class)
    private String contact;

    /** 页面/浏览器/版本上下文 JSON（自动采集，选填） */
    @Size(max = 2000, message = "上下文信息过长", groups = Submit.class)
    private String contextJson;

    @Pattern(regexp = "PENDING|PROCESSING|RESOLVED|CLOSED", message = "状态不合法")
    private String status;

    public interface Submit {
    }
}
