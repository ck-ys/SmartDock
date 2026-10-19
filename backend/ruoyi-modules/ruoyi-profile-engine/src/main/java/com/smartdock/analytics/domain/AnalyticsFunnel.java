package com.smartdock.analytics.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 转化漏斗定义。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("analytics_funnel")
public class AnalyticsFunnel extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "funnel_id", type = IdType.ASSIGN_ID)
    private Long funnelId;

    private String tenantId;

    private String source;

    private String funnelCode;

    private String funnelName;

    /** JSON 数组：[{eventName,name}] */
    private String steps;

    private Integer windowDays;

    private String enabled;

    private String builtin;

    private String remark;
}
