package com.smartdock.profile.event;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 画像事件发布参数(业务侧构建)。
 *
 * @author smartdock
 */
@Data
public class ProfileEventBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务源: dcs / crm / mall ... */
    private String source;

    /** 事件类型(标准词表): task.published / data.submitted ... */
    private String eventType;

    /** 业务自然键(幂等): 建议用 ProfileUids 工具拼 */
    private String eventUid;

    /** 主体类型, 缺省 user */
    private String subjectType = "user";

    /** 主体外部 ID(字符串) */
    private String subjectId;

    /** 业务发生时间; null = 由服务端取当前时刻 */
    private Date occurredTime;

    /** 主体显示名(可选, 用于主体登记) */
    private String subjectName;

    /** 事件属性; 数值属性写成 JSON number(算子取数走 (props->>'k')::numeric) */
    private Map<String, Object> props = new LinkedHashMap<>();
}
