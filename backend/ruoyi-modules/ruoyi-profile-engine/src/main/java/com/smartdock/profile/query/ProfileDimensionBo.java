package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 维度新增/修改参数。
 *
 * @author smartdock
 */
@Data
public class ProfileDimensionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long dimensionId;

    private String source;

    /** 适用主体类型(缺省 user; 员工/会员各自一套) */
    private String subjectType;

    private String dimCode;

    private String dimName;

    /** EVENT_AGG | STRATEGY */
    private String calcMode = "EVENT_AGG";

    private Map<String, Object> calcConfig = new LinkedHashMap<>();

    private BigDecimal scoreMax = BigDecimal.valueOf(100);

    private String enabled = "1";

    private Integer sortOrder = 0;

    private String remark;
}
