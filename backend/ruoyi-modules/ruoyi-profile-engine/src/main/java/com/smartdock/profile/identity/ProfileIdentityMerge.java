package com.smartdock.profile.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 身份合并审计 profile_identity_merge。
 *
 * <p>append-only: 合并只写事件并迁移 identity_link, 不改历史事件;
 * 下游可按 merge 事件重算, 也为二期 unmerge 留出可逆依据。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_identity_merge")
public class ProfileIdentityMerge extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "merge_id", type = IdType.ASSIGN_ID)
    private Long mergeId;

    private String tenantId;

    private String source;

    private String subjectType;

    private String fromSubjectId;

    private String toSubjectId;

    /** login / anonymous_merge / manual ... */
    private String reason;

    private String ruleVersion;

    private String actor;

    private Date occurredAt;

    private Date reversibleUntil;
}
