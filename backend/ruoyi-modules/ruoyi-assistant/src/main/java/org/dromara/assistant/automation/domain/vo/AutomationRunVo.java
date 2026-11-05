package org.dromara.assistant.automation.domain.vo;

import lombok.Data;

/**
 * 运行记录视图（2026-11-05 自动化批）：列表页「最近运行」与编辑页历史共用。
 */
@Data
public class AutomationRunVo {

    private Long id;
    private Long automationId;
    private String automationTitle;
    /** RUNNING / SUCCESS / FAILED / MISSED */
    private String status;
    /** SCHEDULE / MANUAL */
    private String triggerType;
    private String scheduledFor;
    private String startedAt;
    private String finishedAt;
    private Integer durationMs;
    /** 结果会话（点通知/「查看会话」直达） */
    private Long conversationId;
    /** 结果会话 threadId（前端 /agent?thread= 深链） */
    private String threadId;
    private String summary;
    private String error;
}
