package com.smartdock.analytics.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话查询参数。
 *
 * @author smartdock
 */
@Data
public class AnalyticsSessionQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String source;

    /** 最近 N 天（与 fromDate/toDate 二选一） */
    private Integer days = 30;

    private String fromDate;

    private String toDate;

    private String visitorId;

    private String subjectId;

    private String channel;

    private String deviceType;

    private String browser;

    private String country;

    /** 1=只看跳出 */
    private String bounce;

    private String keyword;

    private Integer pageNum = 1;

    private Integer pageSize = 20;
}
