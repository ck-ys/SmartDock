package com.smartdock.profile.monitor;

import java.util.Set;

/**
 * 自监控事件白名单(单源重构 C 片·题1 定死口径)。
 *
 * <p>本产品只记录两类自身事件: 登录成功 + 站内核心操作; 页面浏览一律不记。
 * 白名单是「采用度/两步上手漏斗(登录→首次核心动作)」的判定口径, E 片分析指标
 * 按此取数——名单改动等于改指标口径, 必须连带评估分析侧。</p>
 *
 * <p>自监控事件一律走 {@code publishQuiet}(不触发打分重算; E 片三席回修后仍触发汇总), 见 ProfileEventPublisher。</p>
 *
 * @author smartdock
 */
public final class SelfMonitorEvents {

    /** 登录成功事件(固定事件名; 幂等编号 selfmon.login:userId:雪花ID)。 */
    public static final String LOGIN = "user.login";

    // 站内核心操作(内置任务转发 demo 的六个动作)——生产侧一律引用常量, 禁止裸写字符串:
    // 名单是 E 片采用度/两步漏斗的取数口径, 字面量漂移=指标静默归零(三审 P2)
    public static final String TASK_PUBLISHED = "task.published";
    public static final String DATA_SUBMITTED = "data.submitted";
    public static final String DATA_AUDIT_PASSED = "data.audit_passed";
    public static final String DATA_AUDIT_REJECTED = "data.audit_rejected";
    /** E 片审核岗归属修复: 审核动作另记一条到审核人名下(audit_passed/rejected 主体=提交人)。 */
    public static final String DATA_AUDITED = "data.audited";
    public static final String TASK_CLOSED = "task.closed";

    /** 站内核心操作白名单(事件名与落库 event_name 一致)。 */
    public static final Set<String> CORE_OPERATIONS = Set.of(
        TASK_PUBLISHED, DATA_SUBMITTED, DATA_AUDIT_PASSED, DATA_AUDIT_REJECTED, DATA_AUDITED, TASK_CLOSED);

    /**
     * 「本人主动完成」的操作子集(三席回修 E 片)——不含 audit_passed/rejected:
     * 那两条的主体是被审核的提交人(被动记账), 不是他本人做的动作。
     * 两步上手漏斗/首周激活按此取数, 否则被动通过审核会把没上手的人算成已上手。
     */
    public static final Set<String> ACTED_OPERATIONS = Set.of(
        TASK_PUBLISHED, DATA_SUBMITTED, DATA_AUDITED, TASK_CLOSED);

    /**
     * 是否自监控事件(按事件名判定, 与写入入口无关——CSV 补录进来的同名事件同口径)。
     * 三审 P0: 分析读侧与会话/首见口径据此把自监控事件隔离在访客指标之外。
     */
    public static boolean isSelfMonitor(String eventName) {
        return eventName != null && (LOGIN.equals(eventName) || CORE_OPERATIONS.contains(eventName));
    }

    private SelfMonitorEvents() {
    }
}
