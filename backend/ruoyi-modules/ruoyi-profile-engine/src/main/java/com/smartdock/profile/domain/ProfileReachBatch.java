package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 触达批次（批9 Q3-A）：一次"发通知给分群"或"导出成员留痕"的审计行。
 * channel=inapp 站内通知 / export 成员导出（与批11 导出审批共此审计）。
 *
 * @author smartdock
 */
@Data
@TableName("profile_reach_batch")
public class ProfileReachBatch implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "reach_id", type = IdType.ASSIGN_ID)
    private Long reachId;

    private String tenantId;

    private String source;

    private Long segmentId;

    private String segmentName;

    /** inapp / export */
    private String channel;

    private String title;

    private String content;

    /** 导出是否脱敏（1=脱敏列） */
    private String masked;

    private Integer totalCount;

    private Integer successCount;

    private Integer failCount;

    private Integer retryCount;

    /** RUNNING / DONE / PARTIAL / FAILED */
    private String status;

    private String errorMsg;

    private Long createDept;

    private Long createBy;

    private Date createTime;

    private Long updateBy;

    private Date updateTime;

    private String delFlag;
}
