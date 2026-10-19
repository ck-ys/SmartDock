package com.smartdock.analytics.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * 数据采集站点：siteKey 是公开标识，按它把 SDK 请求映射到业务源。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("analytics_site")
public class AnalyticsSite extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "site_id", type = IdType.ASSIGN_ID)
    private Long siteId;

    private String tenantId;

    private String source;

    private String siteKey;

    private String siteName;

    private String allowedDomains;

    private String enabled;

    private String remark;
}
