package com.smartdock.profile.event;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 画像标准事件实体 profile_event(append-only)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("profile_event")
public class ProfileEvent extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 雪花 ID */
    @TableId(value = "event_id", type = IdType.ASSIGN_ID)
    private Long eventId;

    /** 租户 ID(TenantLine 自动过滤; 显式赋值便于事件灌数脚本) */
    private String tenantId;

    /** 业务源 */
    private String source;

    /** 事件类型(旧字段, 与 eventName 同值保留兼容) */
    private String eventType;

    /** 标准事件名(profile.protocol/v1 词表; 旧接入缺省等于 eventType) */
    private String eventName;

    /** 业务自然键(幂等) */
    private String eventUid;

    /** 主体类型 */
    private String subjectType;

    /** 主体外部 ID(身份解析后的规范主体) */
    private String subjectId;

    /** 匿名 ID(登录前临时主体) */
    private String anonymousId;

    /** 会话 ID(分析中心 PV/UV/访问分析用) */
    private String sessionId;

    /** 事件 schema 版本 */
    private String schemaVersion;

    /** 业务发生时间 */
    private Date occurredTime;

    /** 事件属性(JSON 字符串; 写入走专用 @Insert 带 ::jsonb 强转) */
    private String props;

    /** 上下文(JSON 字符串): page/referrer/utm/device/geo/ip 等 */
    private String context;

    /** 主体特征(JSON 字符串): 事件携带的静态属性快照 */
    private String traits;

    /** 标识符映射(JSON 字符串): user_id/anonymous_id/device_id/email_sha256... */
    private String identifiers;
}
