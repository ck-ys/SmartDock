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

    /** 事件类型(标准词表) */
    private String eventType;

    /** 业务自然键(幂等) */
    private String eventUid;

    /** 主体类型 */
    private String subjectType;

    /** 主体外部 ID */
    private String subjectId;

    /** 业务发生时间 */
    private Date occurredTime;

    /** 事件属性(JSON 字符串; 写入走专用 @Insert 带 ::jsonb 强转) */
    private String props;
}
