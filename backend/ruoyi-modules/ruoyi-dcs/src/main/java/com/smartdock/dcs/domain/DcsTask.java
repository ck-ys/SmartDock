package com.smartdock.dcs.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.smartdock.dcs.support.DcsJsons;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 微核心: 任务主表实体。
 *
 * <p>jsonb 列(assignee/auditor)存 JSON 文本——写入必须走 DcsTaskMapper 定制 SQL 的
 * ::jsonb 强转(批1 实证: MP 标准 insert 对 jsonb 报 42804)。</p>
 *
 * <p>状态机: DRAFT → COLLECTING → CLOSED, 见 {@link com.smartdock.dcs.statemachine.DcsTaskStateMachine}。</p>
 *
 * @author smartdock
 */
@Data
@TableName("dcs_task")
public class DcsTask implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_COLLECTING = "COLLECTING";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String AUDIT_ASSIGNED = "ASSIGNED";
    public static final String AUDIT_PUBLISHER = "PUBLISHER";

    @TableId
    private Long id;

    private String tenantId;

    private String title;

    private String description;

    private Long publisherUserId;

    /** DRAFT / COLLECTING / CLOSED */
    private String status;

    private Date deadline;

    /** 发布时刻(事件翻译的 responseHours 锚点) */
    private Date publishTime;

    /** 目标成员 userId 数组的 JSON 文本(单级, 无组织树/转发) */
    private String assigneeUserIds;

    /** ASSIGNED=指定审核人 / PUBLISHER=发布人直审 */
    private String auditMode;

    /** 指定审核人 userId 数组的 JSON 文本 */
    private String auditorUserIds;

    private Long createDept;

    private Long createBy;

    private Date createTime;

    private Long updateBy;

    private Date updateTime;

    /** 全审回修(glm53/dspro P1): /dcs/task/my 出参辅助列——当前用户最新提交态(PENDING_AUDIT/APPROVED/REJECTED/null) */
    @TableField(exist = false)
    private String mySubmissionStatus;

    /** 便捷读取: 目标成员列表(坏 JSON 容错为空表) */
    public List<Long> assigneeList() {
        return DcsJsons.longList(assigneeUserIds);
    }

    /** 便捷读取: 审核人列表 */
    public List<Long> auditorList() {
        return DcsJsons.longList(auditorUserIds);
    }

    /** 当前用户是否目标成员 */
    public boolean assignedTo(Long userId) {
        return assigneeList().contains(userId);
    }

    /** 当前用户是否本任务的审核人(按审核模式) */
    public boolean auditorIs(Long userId) {
        return AUDIT_ASSIGNED.equals(auditMode) ? auditorList().contains(userId)
            : userId != null && userId.equals(publisherUserId);
    }

    /** 提交时刻距发布时刻的小时数(未发布返回 null)——喂 data.submitted.responseHours */
    public java.math.BigDecimal hoursSincePublish(Date submittedAt) {
        if (publishTime == null || submittedAt == null) {
            return null;
        }
        return BigDecimal.valueOf(Math.max(0, submittedAt.getTime() - publishTime.getTime()))
            .divide(BigDecimal.valueOf(3_600_000L), 2, java.math.RoundingMode.HALF_UP);
    }
}
