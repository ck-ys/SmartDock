package com.smartdock.mission.domain;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 待办口径纯函数守卫(U-03 口径对齐):
 * 「存在 PENDING_AUDIT/APPROVED 提交行」必须与「最新提交为 PA/APPROVED」等价,
 * 服务端 NOT IN 过滤才能让分页 total 与行列表同口径。
 *
 * @author smartdock
 */
@Tag("local")
class TodoPolicyTest {

    private Submission sub(long taskId, int version, String status) {
        Submission s = new Submission();
        s.setTaskId(taskId);
        s.setVersion(version);
        s.setStatus(status);
        return s;
    }

    @Test
    void isTodoMatrix() {
        // 收集中 + 无提交 / 被退回 → 待办
        assertThat(TodoPolicy.isTodo(Task.STATUS_COLLECTING, null)).isTrue();
        assertThat(TodoPolicy.isTodo(Task.STATUS_COLLECTING, Submission.STATUS_REJECTED)).isTrue();
        // 收集中 + 在途/已通过 → 非待办
        assertThat(TodoPolicy.isTodo(Task.STATUS_COLLECTING, Submission.STATUS_PENDING_AUDIT)).isFalse();
        assertThat(TodoPolicy.isTodo(Task.STATUS_COLLECTING, Submission.STATUS_APPROVED)).isFalse();
        // 任务不在收集态一律非待办(无论提交态)
        assertThat(TodoPolicy.isTodo(Task.STATUS_DRAFT, null)).isFalse();
        assertThat(TodoPolicy.isTodo(Task.STATUS_CLOSED, Submission.STATUS_REJECTED)).isFalse();
    }

    @Test
    void notTodoTaskIdsEquivalenceWithLatestSemantics() {
        // taskA: v1 退回→v2 又退回(最新=REJECTED) → 仍是待办, 不入排除集
        // taskB: v1 退回→v2 重提在途(最新=PENDING_AUDIT) → 非待办, 入排除集
        // taskC: v1 已通过(最新=APPROVED) → 非待办, 入排除集
        List<Submission> mine = List.of(
            sub(1001L, 1, Submission.STATUS_REJECTED),
            sub(1001L, 2, Submission.STATUS_REJECTED),
            sub(1002L, 1, Submission.STATUS_REJECTED),
            sub(1002L, 2, Submission.STATUS_PENDING_AUDIT),
            sub(1003L, 1, Submission.STATUS_APPROVED));
        Set<Long> notTodo = TodoPolicy.notTodoTaskIds(mine);

        assertThat(notTodo).containsExactlyInAnyOrder(1002L, 1003L);

        // 等价性: 逐任务按「最高版本行的状态」判 isTodo, 与排除集结论必须一致
        for (long taskId : new long[] {1001L, 1002L, 1003L}) {
            String latest = mine.stream().filter(s -> s.getTaskId() == taskId)
                .max(java.util.Comparator.comparingInt(Submission::getVersion))
                .map(Submission::getStatus).orElse(null);
            boolean todoByLatest = TodoPolicy.isTodo(Task.STATUS_COLLECTING, latest);
            assertThat(todoByLatest).isEqualTo(!notTodo.contains(taskId));
        }
    }

    @Test
    void notTodoTaskIdsDedupAndEmpty() {
        // 同任务多行 PA/APPROVED 只记一次; 无在途/通过行则空集
        Set<Long> ids = TodoPolicy.notTodoTaskIds(List.of(
            sub(7L, 1, Submission.STATUS_PENDING_AUDIT),
            sub(7L, 2, Submission.STATUS_PENDING_AUDIT)));
        assertThat(ids).containsExactly(7L);
        assertThat(TodoPolicy.notTodoTaskIds(List.of())).isEmpty();
        assertThat(TodoPolicy.notTodoTaskIds(List.of(sub(9L, 1, Submission.STATUS_REJECTED)))).isEmpty();
    }
}
