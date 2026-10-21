package com.smartdock.analytics.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 事件回放任务 analytics_replay_task(批10 Q3)。
 *
 * <p>回放=按窗口删派生数据→按 append-only 事件账本重算, 天然幂等;
 * current_day 是断点(下一个待处理日), 中断后 resume 从断点续跑。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("analytics_replay_task")
public class AnalyticsReplayTask extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "replay_id", type = IdType.ASSIGN_ID)
    private Long replayId;

    private String tenantId;

    private String source;

    /** 窗口起(含), 自然日 */
    private Date fromDay;

    /** 窗口止(含), 自然日 */
    private Date toDay;

    /** 勾选的回放链, 逗号分隔: SESSION,DAILY,SNAPSHOT */
    private String chains;

    /** RUNNING / PAUSED / DONE / FAILED */
    private String status;

    /** 断点: 下一个待处理的自然日 */
    private Date currentDay;

    private Integer daysTotal;

    private Integer daysDone;

    /** 回放前对账快照(JSON 文本): 会话数/浏览量/跳出/日汇总行数 */
    private String statsBefore;

    /** 回放后对账快照(JSON 文本), 与 statsBefore 同口径 */
    private String statsAfter;

    private String errorMsg;
}
