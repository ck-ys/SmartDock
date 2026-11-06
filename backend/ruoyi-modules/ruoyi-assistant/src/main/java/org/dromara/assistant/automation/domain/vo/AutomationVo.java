package org.dromara.assistant.automation.domain.vo;

import lombok.Data;

/**
 * 自动化卡片视图（2026-11-05 自动化批）。时间字段统一返回 {@code yyyy-MM-dd HH:mm} 字符串，
 * 前端直接展示，不做时区二次换算（后端固定 GMT+8）。
 */
@Data
public class AutomationVo {

    private Long id;
    private String title;
    private String prompt;
    private String scheduleType;
    private String scheduleTime;
    private String weekdays;

    /** 只读外部服务白名单（逗号分隔；空=没挂） */
    private String mcpServers;
    /** 人话日程：每天 09:00 / 每周一、三 09:00 */
    private String scheduleText;

    /** 1=开 0=停 */
    private String enabled;
    /** 1=系统内置（只读卡片） */
    private String builtin;

    private String nextRunAt;
    private String lastRunAt;
    private String lastStatus;
    private Integer consecutiveFailures;

    /** 已跑次数 / 成功次数（卡片「已跑 N 次」） */
    private int runCount;
    private int successCount;

    private boolean canEdit;
    private boolean canToggle;
}
