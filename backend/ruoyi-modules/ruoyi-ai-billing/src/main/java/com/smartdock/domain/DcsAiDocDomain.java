package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;

/**
 * AI 文档域名白名单(智能识别计费参数 SSRF 防御)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_ai_doc_domain")
public class DcsAiDocDomain extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 域名ID */
    @TableId(value = "domain_id")
    private Long domainId;

    /** 租户ID */
    private String tenantId;

    /** 域名(支持子域匹配) */
    private String domain;

    /** 关联厂商(可选) */
    private String provider;

    /** 备注 */
    private String remark;

    /** 是否启用 */
    private String enabled;

    /** 删除标志 */
    @TableLogic
    private String delFlag;
}
