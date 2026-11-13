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

    /** 记忆类型（ckys3 解锁板题7，2026-11-12）：user/project/team/reference，保存口按关键词推断 */
    private String memoryType;

    /** 出处会话 id（题7；保存时按 threadId 反查，查不到为 null——前端退化为「对话中沉淀」） */
    private Long conversationId;

    /** 同主题键（题8）：命中「称呼/语言/催办默认/时区」这类参数型偏好才有值；同键新条覆盖旧条 */
    private String topicKey;

    /** 状态（题8/题10）：ACTIVE=生效中；SUPERSEDED=被同主题新条替代；ARCHIVED=90 天未用归档 */
    private String status;

    /** 替代它的新条 id（status=SUPERSEDED 时有值，用于回滚溯源） */
    private Long supersededBy;

    /** 最近一次被注入的时间（题10：归档只认「90 天没被注入过」，不用创建时间） */
    private Date lastUsedAt;

    /** 归档时间（status=ARCHIVED 时有值） */
    private Date archivedAt;

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
