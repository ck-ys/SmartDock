package com.smartdock.profile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 分群成员 profile_segment_member(挂在 run_id 下, 保留历史成员快照)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_segment_member")
public class ProfileSegmentMember extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "member_id", type = IdType.ASSIGN_ID)
    private Long memberId;

    private String tenantId;

    private String source;

    private Long segmentId;

    private Long runId;

    private String subjectType;

    private String subjectId;

    private String displayName;

    /** 命中原因/导入来源 */
    private String reason;
}
