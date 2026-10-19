package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 助手会话元数据（批G G2，2026-10-06）。
 *
 * <p>thread 归属首绑载体：(tenant_id, thread_id) 唯一——同一 thread 第二个用户来访即 403
 * （OwnershipResolver 处置链的数据源）。保留 180 天（Q9），清理 job=AssistantRetentionJob。</p>
 */
@Data
@TableName("dcs_assistant_conversation")
public class DcsAssistantConversation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 会话ID（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 归属用户 */
    private Long userId;

    /** AGUI threadId（租户内唯一，归属首绑键） */
    private String threadId;

    /** 会话标题（首条用户消息前 30 字） */
    private String title;

    /** 最后消息时间（列表排序键） */
    private Date lastMessageTime;

    /** ACTIVE/ARCHIVED（本人归档=软通道） */
    private String status;

    /** 会话 TODO 清单 JSON 数组（ZCode 对齐 Q2，批A；todo_write 工具整表替换） */
    /** 任务清单 JSON（ZCode 对齐 Q2 批A）；恢复走 GET /conversation/todo 专用端点，
     * 不随会话列表序列化（dspro P2-7 回修：百行列表每行白背整份清单） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String todoItems;

    /**
     * 该会话是否正在生成（业务批4 Q4，2026-10-12）：列表端点按 run 表 RUNNING
     * thread 集合回填的瞬态标志——刷新后侧栏/列表仍能标出"生成中"的会话。
     * 悬挂兜底=closeStuck job 超时迁移 INTERRUPTED，标志随之自然消失。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean running;

    /** 删除标志（0=存在 1=删除；清理 job/purge 用） */
    private String delFlag;
}
