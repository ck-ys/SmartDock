package org.dromara.assistant.automation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户自建自动化（2026-11-05 拍板 题0 甲/题1 乙/题4 乙）。
 *
 * <p>一条行 = 一句话任务 + 日程 + 开关；执行身份固定为创建人 {@code userId}（审计记本人）。
 * {@code builtin='1'} 是系统内置项（每日摘要，租户级 {@code userId=0}）：只允许开关，
 * 不允许改提示词/删除。</p>
 */
@Data
@TableName("dcs_automation")
public class DcsAutomation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String tenantId;

    /** 创建人 = 运行身份 */
    private Long userId;

    private String title;

    /** 一句话任务（喂给模型的原文） */
    private String prompt;

    /** DAILY / WEEKLY / BUILTIN */
    private String scheduleType;

    /** HH:mm（GMT+8 墙上时间） */
    private String scheduleTime;

    /** 每周几（ISO 1=周一 … 7=周日，逗号分隔） */
    private String weekdays;

    private String timeZone;

    /** 1=开 0=停 */
    private String enabled;

    /** 1=系统内置（只读卡片） */
    private String builtin;

    private LocalDateTime lastRunAt;

    /** SUCCESS / FAILED / MISSED / RUNNING */
    private String lastStatus;

    private Long lastRunId;

    /** 连续失败次数（达到 3 自动停用，题6 甲） */
    private Integer consecutiveFailures;

    /** 下次计划时刻；扫描器认领时先推进此列再执行 */
    private LocalDateTime nextRunAt;

    private Long createBy;

    private LocalDateTime createTime;

    private Long updateBy;

    private LocalDateTime updateTime;

    private String delFlag;
}
