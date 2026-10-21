package com.smartdock.analytics.query;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 回放任务发起参数(批10 Q3)。
 *
 * @author smartdock
 */
@Data
public class AnalyticsReplayBo {

    /** 业务源 */
    @NotBlank(message = "source 不能为空")
    private String source;

    /** 窗口起(含), yyyy-MM-dd */
    @NotBlank(message = "fromDate 不能为空")
    private String fromDate;

    /** 窗口止(含), yyyy-MM-dd */
    @NotBlank(message = "toDate 不能为空")
    private String toDate;

    /** 回放链: SESSION / DAILY / SNAPSHOT 至少一项 */
    private List<String> chains;
}
