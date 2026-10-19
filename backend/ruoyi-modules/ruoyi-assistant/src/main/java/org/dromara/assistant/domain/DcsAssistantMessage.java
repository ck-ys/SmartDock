package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 助手对话原文（批G G2，2026-10-06）。一行一消息（LangGraph 同构，spec §4.1）。
 *
 * <p>X-08 明文例外矩阵：本人会话原文通道允许明文（assistant 侧落库前经 PII 脱敏），
 * 保留 90 天（Q4）自动删；end_reason 让中断与未持久化可判（X-11）。</p>
 */
@Data
@TableName("dcs_assistant_message")
public class DcsAssistantMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 消息ID（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 会话ID */
    private Long conversationId;

    /** 归属用户（会话层双写 userId，S-12 归属校验数据源） */
    private Long userId;

    /** user/assistant */
    private String role;

    /** 消息正文（user=原文；assistant=PII 脱敏后） */
    private String content;

    /** 本消息 token 数（usage 事件可得时填） */
    private Long totalTokens;

    /** 模型名（assistant 侧填） */
    private String model;

    /** normal/stop/error/truncated（X-11） */
    private String endReason;

    /**
     * 工具步骤 JSON(批J-4, 2026-10-06): [{id,name,argsText,brief,status}]——enricher 收集
     * ToolCall 事件随 assistant 消息落库, 历史回放恢复回合 stat/步骤行。
     * resultFull 不落库(体积/PII 面); user 消息恒 null。
     */
    private String toolSteps;

    /**
     * LLM 追问建议 JSON 数组(批5 Q7-3, 2026-10-11): ["q1","q2","q3"]——回答完成后侧车
     * 小调用异步回写；前端 done 后拉取/历史回放装配，空=回退角色静态池。
     */
    private String followupSuggestions;

    /** 创建时间 */
    private Date createTime;
}
