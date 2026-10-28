package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

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

    /** 适用主体类型(题0-乙): 员工/会员各自一套, 缺省 user */
    private String subjectType;

    private String ruleVersion;

    /** 规则显示名(草稿/发布版本共用) */
    private String ruleName;

    /** DRAFT / PUBLISHED / ARCHIVED */
    private String status;

    /** {"completion":0.35,...} 值和=1; 只对启用维度生效, 跳过维度触发归一化 */
    private String weights;

    private String remark;

    private Date publishedAt;

    private Long publishedBy;

    /** 草稿来源版本(用于 diff) */
    private String baseRuleVersion;

    /** 乐观锁版本号 */
    private Integer revision;

    private String enabled;
}
