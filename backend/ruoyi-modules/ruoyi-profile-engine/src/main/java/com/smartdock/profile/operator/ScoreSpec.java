package com.smartdock.profile.operator;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 打分映射规格(calc_config.score): 指标值 → 0~scoreMax 分。
 *
 * @author smartdock
 */
@Data
public class ScoreSpec {

    /** linear(缺省) | bucket */
    private String type = "linear";

    /** linear: 指标下界(映射 0 分, direction=higher 时) */
    private BigDecimal lo = BigDecimal.ZERO;

    /** linear: 指标上界(映射满分) */
    private BigDecimal hi = BigDecimal.ONE;

    /** higher(缺省, 越高越好) | lower(越低越好) */
    private String direction = "higher";

    /** bucket: 门槛列表, 依序首个命中 */
    private List<Bucket> buckets;

    /**
     * 分档: lte 命中即取 score; 无 lte = 兜底档。
     */
    @Data
    public static class Bucket {
        private BigDecimal lte;
        private BigDecimal score;
    }
}
