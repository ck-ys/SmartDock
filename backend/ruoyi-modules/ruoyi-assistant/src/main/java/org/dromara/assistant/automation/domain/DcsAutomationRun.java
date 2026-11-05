package org.dromara.assistant.automation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 自动化运行记录（2026-11-05 拍板 题5 甲/题6 甲）。
 *
 * <p>一次执行一行。{@code scheduled_for}=计划时刻：补跑窗口（6h）之外落 MISSED；
 * 同一条自动化的同一计划时刻有部分唯一索引，多实例/重扫天然去重。</p>
 */
@Data
@TableName("dcs_automation_run")
public class DcsAutomationRun implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    private Long automationId;

    private Long userId;

    /** SCHEDULE / MANUAL */
    private String triggerType;

    private LocalDateTime scheduledFor;

    /** RUNNING / SUCCESS / FAILED / MISSED */
    private String status;

    /** 本次运行落的会话 thread（前端打开会话用） */
    private String threadId;

    private Long conversationId;

    private String resultSummary;

    private String errorMessage;

    private Integer durationMs;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createTime;

    /** 连表展示用（最近运行列表带自动化标题），非表列 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String automationTitle;
}
