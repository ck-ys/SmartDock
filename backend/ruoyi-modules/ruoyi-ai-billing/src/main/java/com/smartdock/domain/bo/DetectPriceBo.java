package com.smartdock.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 智能识别计费参数请求 BO。
 *
 * @author smartdock
 */
@Data
public class DetectPriceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 探测目标模型厂商(见 DcsAiProvider 枚举) */
    private String provider;
    /** 探测目标模型名称(为空时探测该厂商全部模型) */
    private String modelName;
    /** 价格页来源地址(用于爬取/校验官方计费) */
    private String sourceUrl;
}
