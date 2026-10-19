package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 助手 👍👎 反馈（批G G6，2026-10-06，Q13 轻量版）。
 * message_id 关联 dcs_assistant_message（归属校验：只允许评本人会话的消息）；
 * 管理端 feedback/list 查询汇总，喂 eval 数据集（§13）。
 */
@Data
@TableName("dcs_assistant_feedback")
public class DcsAssistantFeedback implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 反馈ID（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 反馈用户 */
    private Long userId;

    /** 关联消息（可空=整会话评价） */
    private Long messageId;

    /** UP=👍 DOWN=👎 */
    private String rating;

    /** 可选备注 */
    private String note;

    /** 创建时间 */
    private Date createTime;
}
