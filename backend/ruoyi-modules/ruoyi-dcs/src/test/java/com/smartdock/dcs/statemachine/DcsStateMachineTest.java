package com.smartdock.dcs.statemachine;

import com.smartdock.dcs.domain.DcsSubmission;
import com.smartdock.dcs.domain.DcsTask;
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
class DcsStateMachineTest {

    @Test
    void taskHappyPath() {
        assertThat(DcsTaskStateMachine.transition(DcsTask.STATUS_DRAFT, DcsTask.STATUS_COLLECTING))
            .isEqualTo(DcsTask.STATUS_COLLECTING);
        assertThat(DcsTaskStateMachine.transition(DcsTask.STATUS_COLLECTING, DcsTask.STATUS_CLOSED))
            .isEqualTo(DcsTask.STATUS_CLOSED);
        // DRAFT 允许直接关闭
        assertThat(DcsTaskStateMachine.canTransition(DcsTask.STATUS_DRAFT, DcsTask.STATUS_CLOSED)).isTrue();
    }

    @Test
    void taskIllegalTransitions() {
        // 终态不可出; 不可回流; 不可跳变
        assertThat(DcsTaskStateMachine.canTransition(DcsTask.STATUS_CLOSED, DcsTask.STATUS_DRAFT)).isFalse();
        assertThat(DcsTaskStateMachine.canTransition(DcsTask.STATUS_COLLECTING, DcsTask.STATUS_DRAFT)).isFalse();
        assertThat(DcsTaskStateMachine.canTransition(DcsTask.STATUS_DRAFT, DcsTask.STATUS_CLOSED)).isTrue();
        assertThatThrownBy(() -> DcsTaskStateMachine.transition(DcsTask.STATUS_CLOSED, DcsTask.STATUS_COLLECTING))
            .isInstanceOf(org.dromara.common.core.exception.ServiceException.class)
            .hasMessageContaining("非法迁移");
        assertThat(DcsTaskStateMachine.isTerminal(DcsTask.STATUS_CLOSED)).isTrue();
    }

    @Test
    void submissionHappyPath() {
        assertThat(DcsSubmissionStateMachine.transition(DcsSubmission.STATUS_PENDING_AUDIT,
            DcsSubmission.STATUS_APPROVED)).isEqualTo(DcsSubmission.STATUS_APPROVED);
        assertThat(DcsSubmissionStateMachine.transition(DcsSubmission.STATUS_PENDING_AUDIT,
            DcsSubmission.STATUS_REJECTED)).isEqualTo(DcsSubmission.STATUS_REJECTED);
        // REJECTED 重提回 PENDING_AUDIT(链上的回边)
        assertThat(DcsSubmissionStateMachine.canTransition(DcsSubmission.STATUS_REJECTED,
            DcsSubmission.STATUS_PENDING_AUDIT)).isTrue();
    }

    @Test
    void submissionIllegalTransitions() {
        // APPROVED 终态不可出; REJECTED 不可直接 APPROVED(必须重提再审)
        assertThat(DcsSubmissionStateMachine.canTransition(DcsSubmission.STATUS_APPROVED,
            DcsSubmission.STATUS_REJECTED)).isFalse();
        assertThat(DcsSubmissionStateMachine.canTransition(DcsSubmission.STATUS_REJECTED,
            DcsSubmission.STATUS_APPROVED)).isFalse();
        assertThat(DcsSubmissionStateMachine.isTerminal(DcsSubmission.STATUS_APPROVED)).isTrue();
        assertThatThrownBy(() -> DcsSubmissionStateMachine.transition(DcsSubmission.STATUS_APPROVED,
            DcsSubmission.STATUS_PENDING_AUDIT)).isInstanceOf(org.dromara.common.core.exception.ServiceException.class);
    }
}
