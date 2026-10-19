package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 维度试算参数(不落库)。
 *
 * @author smartdock
 */
@Data
public class ProfileDimensionPreviewBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 传 dimensionId 时忽略其余配置字段 */
    private Long dimensionId;

    private String source;

    private String subjectType = "user";

    private String subjectId;

    private String dimCode;

    private String dimName;

    private String calcMode = "EVENT_AGG";

    private Map<String, Object> calcConfig = new LinkedHashMap<>();

    private BigDecimal scoreMax = BigDecimal.valueOf(100);
}
