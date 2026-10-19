package com.smartdock.analytics.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 自定义分析：选一个维度和一个指标。
 *
 * @author smartdock
 */
@Data
public class AnalyticsCustomBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String source;

    private Integer days = 30;

    /** day / page / channel / device / browser / os / geo / entry / exit / search / event */
    private String dimension = "day";

    /** pv / uv / visits / events */
    private String metric = "pv";

    private Integer limit = 20;
}
