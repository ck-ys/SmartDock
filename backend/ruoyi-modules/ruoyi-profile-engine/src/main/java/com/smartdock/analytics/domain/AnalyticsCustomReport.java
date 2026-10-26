package com.smartdock.analytics.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 自定义分析视图 analytics_custom_report(R2-4): 分析中心「保存当前视图」落库。
 *
 * <p>按租户+创建人隔离, 同名保存=覆盖(uk 冲突时服务层转更新)。</p>
 *
 * @author smartdock
 */
@Data
@TableName("analytics_custom_report")
public class AnalyticsCustomReport implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "report_id", type = IdType.ASSIGN_ID)
    private Long reportId;

    private String tenantId;

    private String reportName;

    private String source;

    /** day/page/channel/campaign/device/browser/os/geo/entry/exit/search/event */
    private String dimension;

    /** pv/uv/visits/events */
    private String metric;

    private Integer days;

    private Integer limitRows;

    private Long createBy;

    private Date createTime;

    private Long updateBy;

    private Date updateTime;
}
