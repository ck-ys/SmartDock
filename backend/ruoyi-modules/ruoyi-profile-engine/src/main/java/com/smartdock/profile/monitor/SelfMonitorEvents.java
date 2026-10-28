package com.smartdock.profile.monitor;

import java.util.Set;

/**
 * 自监控事件白名单(单源重构 C 片·题1 定死口径)。
 *
 * <p>本产品只记录两类自身事件: 登录成功 + 站内核心操作; 页面浏览一律不记。
 * 白名单是「采用度/两步上手漏斗(登录→首次核心动作)」的判定口径, E 片分析指标
 * 按此取数——名单改动等于改指标口径, 必须连带评估分析侧。</p>
 *
 * <p>自监控事件一律走 {@code publishQuiet}(不触发打分/汇总), 见 ProfileEventPublisher。</p>
 *
 * @author smartdock
 */
public final class SelfMonitorEvents {

    /** 登录成功事件(固定事件名; 幂等编号 selfmon.login:userId:时间戳)。 */
    public static final String LOGIN = "user.login";

    /** 站内核心操作白名单(内置任务转发 demo 的五个动作, 事件名与落库 event_name 一致)。 */
    public static final Set<String> CORE_OPERATIONS = Set.of(
        "task.published",
        "data.submitted",
        "data.audit_passed",
        "data.audit_rejected",
        "task.closed");

    private SelfMonitorEvents() {
    }
}
