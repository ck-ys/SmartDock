package com.smartdock.analytics.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * R2-4 保存自定义分析视图参数。reportId 空=新建; 同名已存在=覆盖更新。
 *
 * @author smartdock
 */
@Data
public class AnalyticsReportSaveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long reportId;

    private String reportName;

    private String source;

    private String dimension;

    private String metric;

    private Integer days;

    private Integer limitRows;
}
