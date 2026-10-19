package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * AI 助手租户配置（批G G1，2026-10-06）。
 *
 * <p>平台默认值={@code AssistantConfigService} 代码常量（spec §4.2），本表只存租户覆盖行；
 * 无行=平台默认=关闭。enabled 即回退急停开关（GQ-21 急停预案的落库形态）；
 * 灰球（开启但未配模型）是派生态不落库，由 status 端点现场计算。</p>
 */
@Data
@TableName("dcs_assistant_config")
public class DcsAssistantConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 租户编号（主键） */
    @TableId(value = "tenant_id", type = IdType.INPUT)
    private String tenantId;

    /** 租户开关：1=开启 0=关闭（急停） */
    private String enabled;

    /** 每用户每日回合上限（Q5，平台默认 50） */
    private Integer dailyRoundLimitPerUser;

    /** 租户日预算（元，Q16，平台默认 50） */
    private BigDecimal dailyBudgetAmount;

    /** 长期记忆开关（P1 恒 0，拍板⑥；1=P2 启用） */
    private String memoryEnabled;

    /** 压缩触发 token 阈值（平台默认 60000，档位 60K/128K/256K） */
    private Integer compactionTriggerTokens;

    /** 每人每日联网搜索次数上限（业务批3-④ #17，平台默认 20；单人覆盖见 dcs_assistant_user_quota.websearch_limit_override） */
    private Integer websearchLimitPerUser;

    /** 每日摘要开关（五需求批 R4b / Q7-C）：1=每日 08:00 给发布人推未交摘要通知，0=关（平台默认） */
    private String digestEnabled;
}
