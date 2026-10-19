package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 模型运行批次 profile_model_run(手动/自动触发, 记录进度与结果)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_model_run")
public class ProfileModelRun extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "run_id", type = IdType.ASSIGN_ID)
    private Long runId;

    private String tenantId;

    private String source;

    private Long modelId;

    private String modelCode;

    private String modelVersion;

    /** RUNNING / DONE / FAILED */
    private String status;

    /** MANUAL / AUTO / INIT */
    private String triggerType;

    private Integer totalSubjects;

    private Integer doneSubjects;

    private String errorMsg;

    private Date startedAt;

    private Date finishedAt;
}
