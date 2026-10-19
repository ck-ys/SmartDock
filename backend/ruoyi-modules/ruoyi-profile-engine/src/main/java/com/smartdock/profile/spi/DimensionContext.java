package com.smartdock.profile.spi;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义维度策略的执行上下文(每次 calc 一个新实例, 单主体)。
 *
 * @author smartdock
 */
@Data
public class DimensionContext {

    /** 租户 ID */
    private final String tenantId;
    /** 业务源 */
    private final String source;
    /** 主体类型 */
    private final String subjectType;
    /** 主体外部 ID */
    private final String subjectId;
    /** 维度编码 */
    private final String dimCode;
    /** 受限只读事件查询口 */
    private final EventQueryPort eventQuery;

    public DimensionContext(String tenantId, String source, String subjectType, String subjectId,
                            String dimCode, EventQueryPort eventQuery) {
        this.tenantId = tenantId;
        this.source = source;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.dimCode = dimCode;
        this.eventQuery = eventQuery;
    }

    /**
     * 快捷取数口(等价 getEventQuery(), 链式少一层)。
     */
    public EventQueryPort events() {
        return eventQuery;
    }

    /** 供策略放证据附注(进快照 dimension_detail[dimCode].evidence) */
    private Map<String, Object> evidenceNotes = new LinkedHashMap<>();
}
