package com.smartdock.profile.engine;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;

/**
 * 主体类型口径(单源重构题0-乙): 评分配置按 (业务源, 主体类型) 分套——
 * 员工与会员同号不串、指标不分摊, 各自一套维度/权重/模型。
 * 缺省 user; 长度与 profile_event.subject_type 同限 32。
 *
 * @author smartdock
 */
public final class SubjectTypes {

    public static final String DEFAULT = "user";

    private SubjectTypes() {
    }

    /** 空白归 user, 其余 trim; 超长直接拒绝。 */
    public static String normalize(String subjectType) {
        String type = StringUtils.isBlank(subjectType) ? DEFAULT : subjectType.trim();
        if (type.length() > 32) {
            throw new ServiceException("subjectType 长度不能超过 32");
        }
        return type;
    }
}
