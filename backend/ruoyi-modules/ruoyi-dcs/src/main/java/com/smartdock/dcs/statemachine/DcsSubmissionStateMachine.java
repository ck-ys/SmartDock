package com.smartdock.dcs.statemachine;

import com.smartdock.dcs.domain.DcsSubmission;

import org.dromara.common.core.exception.ServiceException;

import java.util.Map;
import java.util.Set;

/**
 * 提交状态机(纯函数): PENDING_AUDIT → APPROVED / REJECTED;
 * REJECTED 经"重提新版本"回 PENDING_AUDIT(旧行留痕, 版本递增)。
 *
 * <p>总计划 5 态链的提交侧 3 态(REJECTED 重提=链上的回边)。</p>
 *
 * @author smartdock
 */
public final class DcsSubmissionStateMachine {

    private DcsSubmissionStateMachine() {
    }

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
        DcsSubmission.STATUS_PENDING_AUDIT, Set.of(DcsSubmission.STATUS_APPROVED, DcsSubmission.STATUS_REJECTED),
        DcsSubmission.STATUS_REJECTED, Set.of(DcsSubmission.STATUS_PENDING_AUDIT),
        DcsSubmission.STATUS_APPROVED, Set.of()
    );

    public static boolean canTransition(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static String transition(String from, String to) {
        if (!canTransition(from, to)) {
            throw new ServiceException("提交状态非法迁移: " + from + " → " + to);
        }
        return to;
    }

    public static boolean isTerminal(String status) {
        return DcsSubmission.STATUS_APPROVED.equals(status);
    }
}
