package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 全量重算批次 profile_recalc_batch(断点: last_subject_id 游标)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_recalc_batch")
public class ProfileRecalcBatch extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "batch_id", type = IdType.ASSIGN_ID)
    private Long batchId;

    private String tenantId;

    private String source;

    /** RUNNING / DONE / FAILED */
    private String status;

    private Integer totalSubjects;

    private Integer doneSubjects;

    private Long lastSubjectId;

    private String errorMsg;
}
