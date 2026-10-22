package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 自动压缩记录（批A-2，2026-10-22 拍板 Q9-A）：每次上下文压缩一行，链式可追溯。
 *
 * <p>记数字也记摘要（Q9-A 推荐案）：摘要正文经 PII 脱敏后截断落库，支撑
 * 「它到底忘了什么」的争议回读；分隔条仍只写数字，不把摘要正文塞进消息列表。
 * prev_id 指向上一条同会话记录（首次为 null），与画像快照链同一模式。</p>
 */
@Data
@TableName("dcs_assistant_compaction")
public class DcsAssistantCompaction implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 记录ID（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 会话ID */
    private Long conversationId;

    /** 归属用户 */
    private Long userId;

    /** 框架 threadId（= AGUI sessionId） */
    private String threadId;

    /** 触发方式：AUTO（本批起仅剩自动通道） */
    private String triggerType;

    /** 结果：SUCCESS / FAILED（摘要失败时上下文仍收缩，但记录标 FAILED 供审计） */
    private String status;

    /** 压缩前 token（TokenCounterUtil 同口径） */
    private Integer beforeTokens;

    /** 压缩后 token */
    private Integer afterTokens;

    /** 压缩前消息条数 */
    private Integer beforeMessages;

    /** 压缩后消息条数 */
    private Integer afterMessages;

    /** 摘要正文（落库前过 PII 脱敏、截断 6000 字；供「压掉了什么」审计回读） */
    private String summary;

    /** 原因：AUTO_THRESHOLD（到线自动）/ SUMMARY_FAILED（摘要失败，上下文已收缩但结果不可信）。
     *  注：熔断（rapid_refill/连续失败）不落本表——它不是一次压缩，用户提示走
     *  dcs_assistant_message 的 compaction_blocked system 行 */
    private String reason;

    /** 上一条同会话压缩记录（边界链） */
    private Long prevId;

    /** 记录时间 */
    private Date createTime;
}
