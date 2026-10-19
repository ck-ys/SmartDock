package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 模型分数快照 profile_model_snapshot(与维度快照分链, 便于 RFM 趋势独立演进)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_model_snapshot")
public class ProfileModelSnapshot extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "model_snapshot_id", type = IdType.ASSIGN_ID)
    private Long modelSnapshotId;

    private String tenantId;

    private String source;

    private Long modelId;

    private String modelCode;

    private String modelVersion;

    private String subjectType;

    private String subjectId;

    /** 原始模型分(各维度分 × 权重归一) */
    private BigDecimal modelScore;

    /** 归一化到 100 */
    private BigDecimal normalizedScore;

    /** RFM 八类分层 / 通用高价值档 */
    private String level;

    /** 维度明细 JSON */
    private String detail;

    private Long runId;

    private Long prevSnapshotId;

    private Date calcTime;
}
