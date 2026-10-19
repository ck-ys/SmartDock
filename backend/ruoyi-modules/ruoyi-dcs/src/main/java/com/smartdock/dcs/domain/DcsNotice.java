package com.smartdock.dcs.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 微核心: 站内通知最小集(发布/提交/审核结果)。
 *
 * @author smartdock
 */
@Data
@TableName("dcs_notice")
public class DcsNotice implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String TYPE_TASK_PUBLISHED = "TASK_PUBLISHED";
    public static final String TYPE_SUBMITTED = "SUBMITTED";
    public static final String TYPE_AUDIT_RESULT = "AUDIT_RESULT";

    @TableId
    private Long id;

    private String tenantId;

    private Long userId;

    private String noticeType;

    private String title;

    private String content;

    private Long taskId;

    /** 0=未读 1=已读 */
    private String readFlag;

    private Date createTime;

    /**
     * 业务幂等键（批4 回修：钱包/积分告警族的频次闸）。同一 (tenant,user,key) 只落一条，
     * 见 uk_dcs_notice_dedup 部分唯一索引；普通通知为 null 不参与判重。
     */
    private String dedupKey;
}
