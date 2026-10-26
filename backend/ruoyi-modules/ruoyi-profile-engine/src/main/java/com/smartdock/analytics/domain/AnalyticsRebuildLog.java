package com.smartdock.analytics.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.util.Date;

/**
 * 会话/日汇总重建耗时留痕 analytics_rebuild_log(批10 Q5)。
 *
 * <p>每次重建(手动/自动/回放/拆分)一行, 是引擎健康页「重建耗时分布/趋势」的原料。
 * 行数与(租户×源×重建频次)成正比, 由保留期任务一并清理(默认留 30 天)。</p>
 *
 * @author smartdock
 */
@Data
@TableName("analytics_rebuild_log")
public class AnalyticsRebuildLog {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "log_id", type = IdType.ASSIGN_ID)
    private Long logId;

    private String tenantId;

    private String source;

    /** RECENT(事件后自动) / MANUAL(手动) / REPLAY(回放) / UNMERGE(身份拆分) / DELETION(删除清理) / BACKFILL(批5B每日兜底) */
    private String kind;

    private Long durationMs;

    private Integer sessions;

    private Date finishedAt;

    /** 读取水位: 本次重建实际读到的事件落库时间上限(读取时刻减安全边际), 双实例去重比较用 */
    private Date readWatermark;
}
