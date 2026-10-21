package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 数据删除请求审计(批11 Q2-A): 被遗忘权执行留痕——谁申请/谁执行/删了什么/各多少行/重算了几天。
 *
 * @author smartdock
 */
@Data
@TableName("profile_deletion_log")
public class ProfileDeletionLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long logId;

    private String tenantId;

    private String source;

    private String subjectType;

    private String subjectId;

    /** 预览快照(各类波及计数 JSON) */
    private String preview;

    /** EXECUTED / FAILED */
    private String status;

    private Long eventsDeleted;

    private Long archivesDeleted;

    private Long sessionsDeleted;

    private Long linksDeleted;

    private Long snapshotsDeleted;

    private Long membersDeleted;

    /** 派生重算覆盖的自然日数 */
    private Integer daysRebuilt;

    private String errorMsg;

    private Date executedAt;

    private Long createBy;

    private Date createTime;
}
