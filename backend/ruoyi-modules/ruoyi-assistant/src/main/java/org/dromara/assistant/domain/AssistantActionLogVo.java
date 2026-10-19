package org.dromara.assistant.domain;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 助手运行记录行 VO（二站① AU-52，2026-10-08）：管理端「运行记录」只读页签——
 * 动作账本按租户倒序分页 + 操作人昵称（sys_user 左连，雪花 ID 全局唯一无跨租户碰撞）。
 */
@Data
public class AssistantActionLogVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 动作ID */
    private Long id;

    /** 工具名（如 act_remind；前端用 ACT 词典转中文动词） */
    private String toolName;

    /** PENDING/EXECUTING/DONE/FAILED/REJECTED */
    private String status;

    /** 结果摘要（如「催办已发送（N 人）」） */
    private String resultSummary;

    /** 触发会话（排错锚点，前端截短展示） */
    private String threadId;

    /** 操作人昵称（左连缺行=空串） */
    private String userName;

    /** 时间 */
    private Date createTime;
}
