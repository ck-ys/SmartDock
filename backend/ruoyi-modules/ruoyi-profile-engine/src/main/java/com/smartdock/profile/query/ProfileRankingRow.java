package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 维度排行行。
 *
 * @author smartdock
 */
@Data
public class ProfileRankingRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Integer rank;

    private String subjectType;

    private String subjectId;

    private String displayName;

    private BigDecimal totalScore;

    /** 指定维度分数; 查总分排行时为 null */
    private BigDecimal dimensionScore;

    private Long eventCount;

    private Date lastEventTime;

    private Date calcTime;
}
