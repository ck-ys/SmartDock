package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 助手用户额度覆盖（业务批3-③ #16，2026-10-12）。
 *
 * <p>单人维度的回合/联网次数上限覆盖 + 禁用开关；无行=该用户跟随租户默认
 * （dcs_assistant_config 的 daily_round_limit_per_user / 批④的 websearch_limit_per_user）。
 * (tenant_id, user_id) 唯一——一名用户至多一行事实行，重复操作=更新。</p>
 */
@Data
@TableName("dcs_assistant_user_quota")
public class AssistantUserQuota implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 用户 ID */
    private Long userId;

    /** 单人每日回合上限覆盖：null=跟随租户默认 */
    private Integer roundLimitOverride;

    /** 单人每日联网次数上限覆盖（批④消费）：null=跟随租户默认 */
    private Integer websearchLimitOverride;

    /** 禁用开关：1=管理员暂停该用户的 AI 助手使用 0=正常 */
    private String disabled;
}
