package com.smartdock.mission.statemachine;

import com.smartdock.mission.domain.Task;

import org.dromara.common.core.exception.ServiceException;

import java.util.Map;
import java.util.Set;

/**
 * 任务状态机(纯函数, 无状态): DRAFT → COLLECTING → CLOSED; DRAFT 可直接 close。
 *
 * <p>总计划 5 态链的任务侧 3 态。非法迁移抛 {@link IllegalStateException},
 * 由服务层转 ServiceException——状态机是唯一真相, 禁止散落 if(status==...)。</p>
 *
 * @author smartdock
 */
public final class TaskStateMachine {

    private TaskStateMachine() {
    }

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
        Task.STATUS_DRAFT, Set.of(Task.STATUS_COLLECTING, Task.STATUS_CLOSED),
        Task.STATUS_COLLECTING, Set.of(Task.STATUS_CLOSED),
        Task.STATUS_CLOSED, Set.of()
    );

    /** 该状态是否允许迁往 to */
    public static boolean canTransition(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** 合法则放行, 非法抛 IllegalStateException(带语义信息) */
    public static String transition(String from, String to) {
        if (!canTransition(from, to)) {
            throw new ServiceException("任务状态非法迁移: " + from + " → " + to);
        }
        return to;
    }

    /** 终态判定 */
    public static boolean isTerminal(String status) {
        return Task.STATUS_CLOSED.equals(status);
    }
}
