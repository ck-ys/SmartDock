package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 分群成员行(成员快照 + 主体最近活跃)。
 *
 * @author smartdock
 */
@Data
public class ProfileSegmentMemberRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long memberId;
    private Long runId;
    private String subjectType;
    private String subjectId;
    private String displayName;
    private String reason;
    private Long eventCount;
    private Date lastEventTime;
    private BigDecimal totalScore;
    private Date createTime;
}
