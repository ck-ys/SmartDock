package org.dromara.assistant.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理页统计头 VO（ZCode 对齐 Q4 批C，2026-10-11）：全租户口径四计数 + 本月费用。
 * 页面打开零操作即可见的「体检数字」（五红线①），与工具全景/配置分区同页。
 *
 * @author smartdock
 */
@Data
public class AssistantAdminStatsVo {

    /** 会话总数（dcs_assistant_conversation，含归档） */
    private Long conversations;

    /** 消息总数（dcs_assistant_message，user+assistant 双侧） */
    private Long messages;

    /** 动作执行总数（dcs_assistant_action 账本） */
    private Long actions;

    /** 错答反馈总数（dcs_assistant_feedback） */
    private Long feedbacks;

    /** 本月助手费用（钱包流水 service_code=AI_ASSISTANT_CHAT 且 direction=OUT 的合计，元） */
    private BigDecimal monthCost;
}
