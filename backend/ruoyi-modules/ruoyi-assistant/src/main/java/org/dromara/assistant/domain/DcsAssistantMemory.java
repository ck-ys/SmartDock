package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 助手长期记忆（二站④ AU-53，2026-10-08）。
 *
 * <p>用户级偏好/事实（如「称呼我为小林」「催办默认明天 9 点」）：save_memory 工具
 * 经 {@link org.dromara.assistant.security.AssistantPiiFilter} 脱敏后落库，
 * {@link org.dromara.assistant.core.MemoryInjectModelDecorator} 逐轮注入模型上下文
 * （跨会话生效），记忆页本人可查可删（dcs:assistant:memory:manage）。</p>
 */
@Data
@TableName("dcs_assistant_memory")
public class DcsAssistantMemory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 记忆属主（用户级私有，查询必带谓词） */
    private Long userId;

    /** 记忆内容（入库存前已过 PII 脱敏，上限 200 字） */
    private String content;

    /** 来源：AI=助手对话中沉淀；USER=用户手动（预留） */
    private String source;

    /** 创建部门 */
    private Long createDept;

    /** 创建者 */
    private Long createBy;

    /** 创建时间 */
    private Date createTime;

    /** 更新者 */
    private Long updateBy;

    /** 更新时间 */
    private Date updateTime;

    /** 删除标志（0=在册 2=删除） */
    private String delFlag;
}
