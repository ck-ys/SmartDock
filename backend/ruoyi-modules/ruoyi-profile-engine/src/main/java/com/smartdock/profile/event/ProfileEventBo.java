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

    /** 事件类型(旧接入字段, 与 eventName 二选一): task.published / data.submitted ... */
    private String eventType;

    /** 标准事件名(profile.protocol/v1 词表); 缺省回落到 eventType */
    private String eventName;

    /** 业务自然键(幂等): 建议用 ProfileUids 工具拼 */
    private String eventUid;

    /** 主体类型, 缺省 user */
    private String subjectType = "user";

    /** 主体外部 ID(字符串) */
    private String subjectId;

    /** 匿名 ID(登录前临时主体; 登录事件带 user_id 时会并入规范主体) */
    private String anonymousId;

    /** 会话 ID(分析中心会话语义) */
    private String sessionId;

    /** 事件 schema 版本, 缺省 1-0-0 */
    private String schemaVersion;

    /** 业务发生时间; null = 由服务端取当前时刻 */
    private Date occurredTime;

    /** 主体显示名(可选, 用于主体登记) */
    private String subjectName;

    /** 事件属性; 数值属性写成 JSON number(算子取数走 (props->>'k')::numeric) */
    private Map<String, Object> props = new LinkedHashMap<>();

    /** 上下文: page/referrer/utm/device/geo/ip 等(分析中心取数) */
    private Map<String, Object> context = new LinkedHashMap<>();

    /** 主体特征: 静态属性快照(如 memberLevel/city), 写入主体登记时做 JSONB 合并 */
    private Map<String, Object> traits = new LinkedHashMap<>();

    /** 标识符: user_id/anonymous_id/device_id/email_sha256/phone_sha256... */
    private Map<String, String> identifiers = new LinkedHashMap<>();
}
