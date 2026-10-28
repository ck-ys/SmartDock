package com.smartdock.profile.query;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 画像主视图行(单源重构 M1 B 片·题0-甲): 画像页主列表的数据底座。
 *
 * <p>user 视图以 sys_user 为主表(没事件的本单位用户也列出), 其余身份类型以
 * profile_subject 为主表; 聚合口径跨源——事件数求和、首末时间取极值、快照取最新。</p>
 *
 * @author smartdock
 */
@Data
public class ProfileMainViewRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String subjectType;

    private String subjectId;

    /** 登录名(user 视图来自 sys_user, 其余身份类型为 null) */
    private String userName;

    /** 账号状态(user 视图来自 sys_user: '0'正常/'1'停用, 其余身份类型为 null) */
    private String userStatus;

    /** 显示名: user 视图取昵称兜底登录名, 其余类型取最近活跃的主体登记名 */
    private String displayName;

    /** 跨源事件数求和(user 视图无数据为 0) */
    private Long eventCount;

    /** 跨源首事件时间极值 */
    private Date firstEventTime;

    /** 跨源末事件时间极值 */
    private Date lastEventTime;

    /** 跨源最新快照总分(无快照为 null; 无列级分数权限时服务端置空) */
    private BigDecimal totalScore;

    /** 最新快照的计算时间 */
    private Date calcTime;

    /** 最新快照是否「未配置打分口径」占位(50 分只是占位不是评价, 前端显示专用标签) */
    private Boolean noDimensions;

    /** 是否有待处理的低分复核(前端「待联系」动作列锚点) */
    private Boolean pendingReview;
}
