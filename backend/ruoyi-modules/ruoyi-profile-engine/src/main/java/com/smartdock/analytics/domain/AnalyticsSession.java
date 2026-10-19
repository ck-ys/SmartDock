package com.smartdock.analytics.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 一次访问（会话）。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("analytics_session")
public class AnalyticsSession extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "session_id", type = IdType.ASSIGN_ID)
    private Long sessionId;

    private String tenantId;

    private String source;

    /** 会话稳定键：外部 sessionId 或 visitor + 30 分钟窗口 */
    private String sessionKey;

    /** 访问者稳定标识：登录主体优先，其次匿名 ID */
    private String visitorId;

    private String subjectType;

    private String subjectId;

    private String anonymousId;

    /** 1 = 首次访问该业务源 */
    private String isNewVisitor;

    private Date startedAt;

    private Date endedAt;

    private Date lastEventAt;

    private Integer durationSeconds;

    private Integer pageViews;

    private Integer eventCount;

    /** 1 = 只浏览了一个页面 */
    private String isBounce;

    private String entryPage;

    private String exitPage;

    private String referrer;

    private String sourceChannel;

    private String campaign;

    private String deviceType;

    private String os;

    private String browser;

    private String country;

    private String province;

    private String city;

    private String searchTerm;
}
