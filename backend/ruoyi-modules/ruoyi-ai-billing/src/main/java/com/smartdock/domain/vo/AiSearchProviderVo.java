package com.smartdock.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 搜索服务厂商目录项（Q16=1）：「新增」下拉的候选。
 *
 * <p>{@code balanceSupported=false} 表示这家厂商没有余额查询接口——
 * 界面据此把「查余额」按钮置灰并写「去它的控制台看」，而不是点下去报错。</p>
 */
@Data
public class AiSearchProviderVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String provider;
    /** 给人看的名字（如「博查 Bocha」） */
    private String name;
    private String consoleUrl;
    private Boolean balanceSupported;
    /** 计费口径一句话（按量单价 / 资源包），给管理员看的 */
    private String unitPrice;
}
