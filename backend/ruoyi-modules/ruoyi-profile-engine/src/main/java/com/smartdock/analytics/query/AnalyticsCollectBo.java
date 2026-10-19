package com.smartdock.analytics.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集端批量上报参数。
 *
 * @author smartdock
 */
@Data
public class AnalyticsCollectBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String siteKey;

    private List<CollectEvent> events = new ArrayList<>();

    @Data
    public static class CollectEvent implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String eventName;

        private String eventUid;

        private String userId;

        private String anonymousId;

        private String sessionId;

        private Date occurredTime;

        private String subjectName;

        private Map<String, Object> props = new LinkedHashMap<>();

        private Map<String, Object> context = new LinkedHashMap<>();
    }
}
