package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 画像引擎配置参数。
 *
 * @author smartdock
 */
@Data
public class ProfileEngineConfigBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String source;

    /** 1/0 */
    private String autoRecalc;

    /** 1/0 */
    private String reviewEnabled;

    private BigDecimal reviewThreshold;

    private Integer debounceMs;

    private String remark;
}
