package com.smartdock.risk.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Score calculated for one enabled event rule using that rule's own window.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleScoreVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String eventType;

    private Integer score;

    private Integer level;
}
