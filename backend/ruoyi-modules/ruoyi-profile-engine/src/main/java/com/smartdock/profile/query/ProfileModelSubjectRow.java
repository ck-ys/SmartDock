package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 模型主体行(最新模型快照 + 主体信息)。
 *
 * @author smartdock
 */
@Data
public class ProfileModelSubjectRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String subjectType;
    private String subjectId;
    private String displayName;
    private Long eventCount;
    private Date lastEventTime;
    private BigDecimal modelScore;
    private BigDecimal normalizedScore;
    private String level;
    /** 维度明细 JSON 字符串 */
    private String detail;
    private String modelVersion;
    private Date calcTime;
}
