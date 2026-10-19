package com.smartdock.analytics.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 按天汇总的流量指标。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("analytics_daily")
public class AnalyticsDaily extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "daily_id", type = IdType.ASSIGN_ID)
    private Long dailyId;

    private String tenantId;

    private String source;

    private Date statDate;

    private Long pv;

    private Long uv;

    private Long visits;

    private Long events;

    private Long newVisitors;

    private Long returningVisitors;

    private Long bounces;

    private Long durationSeconds;
}
