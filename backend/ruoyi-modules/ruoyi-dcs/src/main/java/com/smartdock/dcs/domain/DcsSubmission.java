package com.smartdock.dcs.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 微核心: 提交(单次 + 版本号)。
 *
 * <p>状态机: PENDING_AUDIT → APPROVED / REJECTED; REJECTED 重提 = 新 version 行,
 * 旧行留痕。data 存 {fieldCode: value} 的 JSON 文本(jsonb 写入走定制 SQL)。</p>
 *
 * @author smartdock
 */
@Data
@TableName("dcs_submission")
public class DcsSubmission implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String STATUS_PENDING_AUDIT = "PENDING_AUDIT";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    @TableId
    private Long id;

    private String tenantId;

    private Long taskId;

    private Long submitterUserId;

    private Integer version;

    /** PENDING_AUDIT / APPROVED / REJECTED */
    private String status;

    /** {fieldCode: value} 的 JSON 文本 */
    private String data;

    private Date submittedAt;

    private Date auditedAt;

    private Date createTime;
}
