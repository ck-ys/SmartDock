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
 * 通用评分模型 profile_score_model(配置版本化, 快照按 model_version 留痕)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_score_model")
public class ProfileScoreModel extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "model_id", type = IdType.ASSIGN_ID)
    private Long modelId;

    private String tenantId;

    private String source;

    private String modelCode;

    private String modelName;

    /** GENERIC / RFM */
    private String modelType;

    private String modelVersion;

    /** ProfileModelDefinition JSON */
    private String config;

    private BigDecimal scoreMax;

    private String enabled;

    private String builtin;

    private String remark;

    /** 乐观锁: 每次编辑 +1, 运行记录使用编辑时的版本 */
    private Integer revision;
}
