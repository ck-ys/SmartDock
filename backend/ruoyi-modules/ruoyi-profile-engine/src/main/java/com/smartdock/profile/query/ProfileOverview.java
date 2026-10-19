package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 画像总览指标。
 *
 * @author smartdock
 */
@Data
public class ProfileOverview implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long subjectCount;

    private Long scoredCount;

    private BigDecimal avgScore;

    private Long lowRisk;

    private Long mediumRisk;

    private Long highRisk;

    private Date lastCalcTime;
}
