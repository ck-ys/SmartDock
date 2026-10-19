package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分群新增/修改参数。
 *
 * @author smartdock
 */
@Data
public class ProfileSegmentBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long segmentId;

    private String source;

    private String segmentCode;

    private String segmentName;

    /** DYNAMIC / STATIC */
    private String segmentType = "DYNAMIC";

    /** ENABLED / DISABLED */
    private String status = "ENABLED";

    /** ProfileSegmentRule JSON 对象 */
    private Map<String, Object> ruleConfig = new LinkedHashMap<>();

    private String description;

    private Integer revision;
}
