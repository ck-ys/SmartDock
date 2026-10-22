package com.smartdock.analytics.query;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据接入页·新建站点入参（2026-10-22 拍板·全按推荐）：起名即建——
 * siteKey 由系统随机签发，业务源固定 web；域名白名单选填（空=不限）。
 *
 * @author smartdock
 */
@Data
public class AnalyticsSiteCreateBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 站点名（用户起的名字，如「公司官网」） */
    @NotBlank(message = "站点名不能为空")
    @Size(max = 64, message = "站点名不能超过 64 字")
    private String siteName;

    /** 允许的来源域名，逗号分隔（可选；空 = 不限制） */
    @Size(max = 255, message = "允许域名不能超过 255 字")
    private String allowedDomains;
}
