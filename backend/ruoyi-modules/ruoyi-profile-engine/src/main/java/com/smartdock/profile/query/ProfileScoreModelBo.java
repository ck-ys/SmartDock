package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 评分模型新增/修改参数。
 *
 * @author smartdock
 */
@Data
public class ProfileScoreModelBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long modelId;

    private String source;

    private String modelCode;

    private String modelName;

    /** GENERIC / RFM; 缺省从 config.preset 推导 */
    private String modelType;

    private String modelVersion;

    /** ProfileModelDefinition JSON 对象 */
    private Map<String, Object> config = new LinkedHashMap<>();

    private BigDecimal scoreMax = BigDecimal.valueOf(100);

    private String enabled = "1";

    private String remark;

    private Integer revision;
}
