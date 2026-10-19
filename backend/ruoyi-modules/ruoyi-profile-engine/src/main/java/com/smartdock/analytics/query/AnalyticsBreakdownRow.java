package com.smartdock.analytics.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 维度下钻行。
 *
 * @author smartdock
 */
@Data
public class AnalyticsBreakdownRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String label;

    private Long pv;

    private Long uv;

    private Long visits;

    private Long events;

    private BigDecimal share;
}
