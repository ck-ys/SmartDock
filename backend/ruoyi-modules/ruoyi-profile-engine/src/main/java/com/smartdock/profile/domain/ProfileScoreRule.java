package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 权重规则 profile_score_rule。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_score_rule")
public class ProfileScoreRule extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "rule_id", type = IdType.ASSIGN_ID)
    private Long ruleId;

    private String tenantId;

    private String source;

    private String ruleVersion;

    /** {"completion":0.35,...} 值和=1; 只对启用维度生效, 跳过维度触发归一化 */
    private String weights;

    private String enabled;
}
