package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 助手动作账本（批G G3，2026-10-06，spec §8）。
 * HITL 确认后的写操作全生命周期留痕：用户拒绝也落 REJECTED（审计闭环）；
 * 幂等键 {@code assistant:{tenantId}:{runId}:{seq}} 唯一索引防双发（@RepeatSubmit 的
 * Reactor 替代件，S-14）。
 */
@Data
@TableName("dcs_assistant_action")
public class DcsAssistantAction implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 动作ID（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 归属用户 */
    private Long userId;

    /** 触发 run */
    private String runId;

    /** 触发 thread */
    private String threadId;

    /** 工具名（如 act_remind） */
    private String toolName;

    /** 结构化参数 JSON（确认卡同源；text 列，查询侧可 ::jsonb 后转——DDL 偏差登记） */
    private String argsJson;

    /** PENDING/EXECUTING/DONE/FAILED/REJECTED */
    private String status;

    /** 结果摘要（回执口径，如「催办已发送（N 人）」） */
    private String resultSummary;

    /** 幂等键（唯一索引） */
    private String idempotencyKey;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}
