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
 * 分群运行批次 profile_segment_run(每次评估=一次成员快照)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_segment_run")
public class ProfileSegmentRun extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "run_id", type = IdType.ASSIGN_ID)
    private Long runId;

    private String tenantId;

    private String source;

    private Long segmentId;

    /** MANUAL / IMPORT / INIT */
    private String triggerType;

    /** RUNNING / DONE / FAILED */
    private String status;

    private Integer totalCandidates;

    private Integer memberCount;

    private String errorMsg;

    private Date startedAt;

    private Date finishedAt;
}
