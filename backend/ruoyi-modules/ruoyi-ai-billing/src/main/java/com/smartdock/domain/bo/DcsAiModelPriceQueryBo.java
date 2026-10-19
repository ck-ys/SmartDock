package com.smartdock.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 模型价格查询对象。
 *
 * @author smartdock
 */
@Data
public class DcsAiModelPriceQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 查询用:租户编号 */
    private String tenantId;
    /** 查询用:模型厂商(见 DcsAiProvider 枚举) */
    private String provider;
    /** 查询用:模型名称 */
    private String modelName;
    /** 查询用:是否启用 1=启用 0=停用 */
    private String enabled;
}
