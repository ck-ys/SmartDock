package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 主体列表行: 主体登记 + 最新快照。
 *
 * @author smartdock
 */
@Data
public class ProfileSubjectRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String tenantId;

    private String source;

    private String subjectType;

    private String subjectId;

    private String displayName;

    private Date firstEventTime;

    private Date lastEventTime;

    private Long eventCount;

    /** JSON 字符串 */
    private String properties;

    private BigDecimal totalScore;

    /** JSON 字符串(dimCode -> {score, normalizedScore, weight, tags}) */
    private String dimensionDetail;

    private String ruleVersion;

    private Date calcTime;
}
