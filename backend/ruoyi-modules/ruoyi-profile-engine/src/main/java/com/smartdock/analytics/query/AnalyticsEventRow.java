package com.smartdock.analytics.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 分析用事件行（只投影需要的列，避免把整张事件表读进内存）。
 *
 * @author smartdock
 */
@Data
public class AnalyticsEventRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long eventId;

    private String eventName;

    private String subjectType;

    private String subjectId;

    private String anonymousId;

    private String sessionId;

    /** COALESCE(anonymous_id, subject_id) */
    private String visitorKey;

    private Date occurredTime;

    /** JSON 字符串 */
    private String props;

    /** JSON 字符串 */
    private String context;
}
