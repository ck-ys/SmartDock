package org.dromara.assistant.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 技能使用统计（题2 案3，2026-10-31 拍板）：一技一行，只记次数与最近一次。
 *
 * <p>不存逐次调用明细（题2 案3 明确不做归档/扫描）；键是 (tenant_id, skill_id)，
 * 内置技能没有 {@code dcs_assistant_skill} 在线行也能统计。</p>
 *
 * @author smartdock
 */
@Data
@TableName("dcs_assistant_skill_usage")
public class DcsAssistantSkillUsage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花） */
    @TableId(value = "id")
    private Long id;

    /** 租户编号 */
    private String tenantId;

    /** 技能标识（与技能目录同 id） */
    private String skillId;

    /** 成功取用次数 */
    private Long useCount;

    /** 最近一次取用时间 */
    private java.util.Date lastUsedAt;

    /** 行更新时间 */
    private java.util.Date updateTime;
}
