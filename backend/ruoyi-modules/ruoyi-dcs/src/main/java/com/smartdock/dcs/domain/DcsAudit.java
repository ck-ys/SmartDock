package com.smartdock.dcs.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 微核心: 审核决定(PASS/REJECT), 追加式留痕。
 *
 * @author smartdock
 */
@Data
@TableName("dcs_audit")
public class DcsAudit implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String DECISION_PASS = "PASS";
    public static final String DECISION_REJECT = "REJECT";

    @TableId
    private Long id;

    private String tenantId;

    private Long submissionId;

    private Long taskId;

    private Long auditorUserId;

    /** PASS / REJECT */
    private String decision;

    private String comment;

    private Date createTime;
}
