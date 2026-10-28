package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 权重规则草稿新增/修改参数。
 *
 * @author smartdock
 */
@Data
public class ProfileRuleBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long ruleId;

    private String source;

    /** 适用主体类型(缺省 user; 员工/会员各自一套) */
    private String subjectType;

    /** 新建草稿时的复制来源(已发布/已归档版本) */
    private Long sourceRuleId;

    private String ruleName;

    private Map<String, Double> weights = new LinkedHashMap<>();

    private String remark;

    /** 草稿乐观锁版本号; 新建时忽略 */
    private Integer revision;
}
