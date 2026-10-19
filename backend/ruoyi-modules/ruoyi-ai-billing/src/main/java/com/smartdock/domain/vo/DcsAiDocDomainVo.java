package com.smartdock.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.domain.DcsAiDocDomain;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * AI 文档域名白名单视图对象。
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = DcsAiDocDomain.class)
public class DcsAiDocDomainVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long domainId;
    private String tenantId;
    private String domain;
    private String provider;
    private String remark;
    private String enabled;
    private Date createTime;
}
