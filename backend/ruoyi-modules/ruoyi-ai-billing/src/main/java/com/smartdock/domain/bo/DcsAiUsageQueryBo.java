package com.smartdock.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 消耗台账查询 BO。
 *
 * @author smartdock
 */
@Data
public class DcsAiUsageQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 调用方筛选 */
    private String caller;

    /** 场景筛选 */
    private String scene;

    /** 配置 ID 筛选 */
    private Long configId;
}
