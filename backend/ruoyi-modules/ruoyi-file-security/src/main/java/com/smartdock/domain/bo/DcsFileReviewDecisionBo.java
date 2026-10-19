package com.smartdock.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文件审核驳回入参（12b）——驳回单条 + 理由必填
 *
 * @author smartdock
 */
@Data
public class DcsFileReviewDecisionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 审核单ID */
    @NotNull(message = "审核单ID不能为空")
    private Long reviewId;

    /** 驳回理由（2-500 字，随通知发给上传人；预设四条快选见前端 fileReviewPresentation） */
    @NotBlank(message = "驳回理由不能为空")
    @Size(min = 2, max = 500, message = "驳回理由长度须在2到500个字符之间")
    private String reason;
}
