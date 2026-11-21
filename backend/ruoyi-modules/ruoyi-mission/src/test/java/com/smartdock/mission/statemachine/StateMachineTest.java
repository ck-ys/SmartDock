package com.smartdock.mission.statemachine;

import com.smartdock.mission.domain.Submission;
import com.smartdock.mission.domain.Task;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 双状态机合法/非法迁移守卫(纯函数, design-mini-core §3)。
 *
 * @author smartdock
 */
@Tag("local")
class StateMachineTest {

    @Test
    void taskHappyPath() {
        assertThat(TaskStateMachine.transition(Task.STATUS_DRAFT, Task.STATUS_COLLECTING))
            .isEqualTo(Task.STATUS_COLLECTING);
        assertThat(TaskStateMachine.transition(Task.STATUS_COLLECTING, Task.STATUS_CLOSED))
            .isEqualTo(Task.STATUS_CLOSED);
        // DRAFT 允许直接关闭
        assertThat(TaskStateMachine.canTransition(Task.STATUS_DRAFT, Task.STATUS_CLOSED)).isTrue();
    }

    @Test
    void taskIllegalTransitions() {
        // 终态不可出; 不可回流; 不可跳变
        assertThat(TaskStateMachine.canTransition(Task.STATUS_CLOSED, Task.STATUS_DRAFT)).isFalse();
        assertThat(TaskStateMachine.canTransition(Task.STATUS_COLLECTING, Task.STATUS_DRAFT)).isFalse();
        assertThat(TaskStateMachine.canTransition(Task.STATUS_DRAFT, Task.STATUS_CLOSED)).isTrue();
        assertThatThrownBy(() -> TaskStateMachine.transition(Task.STATUS_CLOSED, Task.STATUS_COLLECTING))
            .isInstanceOf(org.dromara.common.core.exception.ServiceException.class)
            .hasMessageContaining("非法迁移");
        assertThat(TaskStateMachine.isTerminal(Task.STATUS_CLOSED)).isTrue();
    }

    @Test
    void submissionHappyPath() {
        assertThat(SubmissionStateMachine.transition(Submission.STATUS_PENDING_AUDIT,
            Submission.STATUS_APPROVED)).isEqualTo(Submission.STATUS_APPROVED);
        assertThat(SubmissionStateMachine.transition(Submission.STATUS_PENDING_AUDIT,
            Submission.STATUS_REJECTED)).isEqualTo(Submission.STATUS_REJECTED);
        // REJECTED 重提回 PENDING_AUDIT(链上的回边)
        assertThat(SubmissionStateMachine.canTransition(Submission.STATUS_REJECTED,
            Submission.STATUS_PENDING_AUDIT)).isTrue();
    }

    @Test
    void submissionIllegalTransitions() {
        // APPROVED 终态不可出; REJECTED 不可直接 APPROVED(必须重提再审)
        assertThat(SubmissionStateMachine.canTransition(Submission.STATUS_APPROVED,
            Submission.STATUS_REJECTED)).isFalse();
        assertThat(SubmissionStateMachine.canTransition(Submission.STATUS_REJECTED,
            Submission.STATUS_APPROVED)).isFalse();
        assertThat(SubmissionStateMachine.isTerminal(Submission.STATUS_APPROVED)).isTrue();
        assertThatThrownBy(() -> SubmissionStateMachine.transition(Submission.STATUS_APPROVED,
            Submission.STATUS_PENDING_AUDIT)).isInstanceOf(org.dromara.common.core.exception.ServiceException.class);
    }
}
