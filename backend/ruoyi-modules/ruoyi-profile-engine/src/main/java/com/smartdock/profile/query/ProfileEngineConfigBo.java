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

    /** 复核 SLA 天数(1~365) */
    private Integer reviewSlaDays;

    /** 事件保留天数(批10 Q4, 1~36500; null=继承全局) */
    private Integer eventRetentionDays;

    /** 过期去向 1/0: 1=归档后删(默认), 0=直接删 */
    private String eventArchive;
}
