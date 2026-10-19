package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * 维度治理 profile_score_dimension。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_score_dimension")
public class ProfileScoreDimension extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "dimension_id", type = IdType.ASSIGN_ID)
    private Long dimensionId;

    private String tenantId;

    private String dimCode;

    private String dimName;

    /** 归属业务源(维度按 source 隔离) */
    private String source;

    /** EVENT_AGG(JSON 配置) | STRATEGY(Java Bean) */
    private String calcMode;

    /** EVENT_AGG: {metric,window,score,missing}; STRATEGY: {strategyBean} */
    private String calcConfig;

    private BigDecimal scoreMax;

    private String enabled;

    private String builtin;

    private Integer sortOrder;

    private String remark;
}
