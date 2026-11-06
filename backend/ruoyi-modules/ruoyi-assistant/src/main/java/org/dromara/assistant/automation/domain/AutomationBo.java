package org.dromara.assistant.automation.domain;

import lombok.Data;

/**
 * 新建/编辑自动化的提交体（2026-11-05 自动化批）。
 *
 * <p>日程只收「每天/每周几 + HH:mm」，不收 cron（题4 乙）。校验在 service 层做，
 * 以便返回中文错误而不是框架校验消息。</p>
 */
@Data
public class AutomationBo {

    private String title;

    /** 一句话任务：模型到点自己挑技能/只读工具（题1 乙） */
    private String prompt;

    /** DAILY / WEEKLY */
    private String scheduleType;

    /** HH:mm */
    private String scheduleTime;

    /** 每周几：ISO 1=周一 … 7=周日，逗号分隔 */
    private String weekdays;

    /** 只读外部服务：逗号分隔的 serverName；不传/空=不挂任何外部工具（默认关） */
    private String mcpServers;
}
