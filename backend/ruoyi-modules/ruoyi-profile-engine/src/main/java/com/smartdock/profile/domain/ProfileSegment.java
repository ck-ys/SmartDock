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
 * 标签分群 profile_segment(动态规则/静态导入共用)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_segment")
public class ProfileSegment extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "segment_id", type = IdType.ASSIGN_ID)
    private Long segmentId;

    private String tenantId;

    private String source;

    private String segmentCode;

    private String segmentName;

    /** DYNAMIC / STATIC */
    private String segmentType;

    /** ENABLED / DISABLED */
    private String status;

    /** 动态规则 JSON; 静态分群为空对象 */
    private String ruleConfig;

    private String description;

    private Integer memberCount;

    private Long lastRunId;

    private Date lastEvalTime;

    /** OFF / DAILY / HOURLY（批9 定时刷新模式） */
    private String refreshMode;

    private String builtin;

    private Integer revision;
}
