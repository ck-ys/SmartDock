package com.smartdock.analytics.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 漏斗新增/修改参数。
 *
 * @author smartdock
 */
@Data
public class AnalyticsFunnelBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long funnelId;

    private String source;

    private String funnelCode;

    private String funnelName;

    /** [{eventName,name}] */
    private List<Map<String, Object>> steps = new ArrayList<>();

    private Integer windowDays = 7;

    private String enabled = "1";

    private String remark;

    /** 预览用：临时步骤/窗口，不落库 */
    private Boolean preview;

    private Integer days = 30;
}
