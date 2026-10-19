package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 低分复核处理参数。
 *
 * @author smartdock
 */
@Data
public class ProfileReviewActionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 复核结论说明 */
    private String note;

    /** 可选: 人工改判的风险级别 */
    private String riskLevel;
}
