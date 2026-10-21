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

    /** 批11 Q3: 签名密钥(HMAC-SHA256), 只在服务端与站点管理员可见, SDK 嵌入端持有 */
    private String signSecret;

    /** 批11 Q3: 签名开关 1=强制验签(无签名/过期/重放整批拒绝) 0=兼容存量明文上报 */
    private String signEnabled;

    private String remark;
}
