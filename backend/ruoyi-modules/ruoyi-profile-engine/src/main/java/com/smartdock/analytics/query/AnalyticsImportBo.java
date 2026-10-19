package com.smartdock.analytics.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * CSV 导入参数：表头 + 数据行，至少包含 eventName / occurredTime。
 *
 * @author smartdock
 */
@Data
public class AnalyticsImportBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String source;

    private String csv;
}
