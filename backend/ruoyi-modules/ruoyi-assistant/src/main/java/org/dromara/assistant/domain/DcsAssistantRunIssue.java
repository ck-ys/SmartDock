package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 「这一轮为什么没回答」问题记录（2026-11-11 拍板 Q1-3：独立问题表，不动老表）。
 *
 * <p>一轮至多一条现行记录（run_id 唯一索引 + ON CONFLICT 兜底）；message_id 指向该轮的用户提问行——
 * 历史回放按它把原因挂回对应回合。detail 只存脱敏后的服务商原文（Q2-2），
 * 普通用户看前端按 code 映射的人话，原文仅管理员可见。保留 90 天（消息表为 180 天，本表更短）。</p>
 */
@Data
@TableName("dcs_assistant_run_issue")
public class DcsAssistantRunIssue implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 行ID（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 归属用户 */
    private Long userId;

    /** 会话ID（run 行能查到会话时填；查不到为空，活体接口仍可按 runId 读） */
    private Long conversationId;

    /** AGUI threadId */
    private String threadId;

    /** AGUI runId */
    private String runId;

    /** 该轮用户提问的消息行 id（历史回放挂靠锚点） */
    private Long messageId;

    /** empty/rate/quota/timeout/auth/other */
    private String code;

    /** 服务商原文（脱敏 + 200 字截断；empty 类为空） */
    private String detail;

    /** 创建时间 */
    private Date createTime;
}
