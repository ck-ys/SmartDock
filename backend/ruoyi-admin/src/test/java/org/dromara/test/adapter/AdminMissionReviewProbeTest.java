package org.dromara.test.adapter;

import com.smartdock.dcs.domain.DcsTask;
import com.smartdock.dcs.mapper.DcsTaskMapper;
import org.dromara.web.adapter.AdminMissionReviewProbe;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 任务审核人探针适配器单测（批4 端口补齐，2026-10-18）。
 *
 * <p>file-security 的 MissionReviewProbe 端口在微核心口径下的语义：
 * 「在审审核人」= 任务 COLLECTING 且我是其审核人（指定审核人模式查名单，
 * 发布人直审模式看 publisherUserId）。</p>
 */
@Tag("dev")
class AdminMissionReviewProbeTest {

    private final DcsTaskMapper taskMapper = mock(DcsTaskMapper.class);
    private final AdminMissionReviewProbe probe = new AdminMissionReviewProbe(taskMapper);

    @Test
    void collectingAssignedAuditorSeesTrue() {
        DcsTask task = new DcsTask();
        task.setStatus(DcsTask.STATUS_COLLECTING);
        task.setAuditMode(DcsTask.AUDIT_ASSIGNED);
        task.setAuditorUserIds("[9100000001, 9100000002]");
        when(taskMapper.selectOne(any())).thenReturn(task);

        assertTrue(probe.hasPendingReviewTask(1L, 9100000002L));
    }

    @Test
    void collectingPublisherDirectAuditorSeesTrue() {
        DcsTask task = new DcsTask();
        task.setStatus(DcsTask.STATUS_COLLECTING);
        task.setAuditMode(DcsTask.AUDIT_PUBLISHER);
        task.setPublisherUserId(1L);
        when(taskMapper.selectOne(any())).thenReturn(task);

        assertTrue(probe.hasPendingReviewTask(1L, 1L));
        assertFalse(probe.hasPendingReviewTask(1L, 2L));
    }

    @Test
    void strangerSeesFalseEvenWhenTaskCollecting() {
        DcsTask task = new DcsTask();
        task.setStatus(DcsTask.STATUS_COLLECTING);
        task.setAuditMode(DcsTask.AUDIT_ASSIGNED);
        task.setAuditorUserIds("[9100000001]");
        when(taskMapper.selectOne(any())).thenReturn(task);

        assertFalse(probe.hasPendingReviewTask(1L, 9100000009L));
    }

    @Test
    void nonCollectingTaskYieldsNullRowAndFalse() {
        // selectOne 带 status=COLLECTING 谓词, 关闭/草稿任务查不到行
        when(taskMapper.selectOne(any())).thenReturn(null);

        assertFalse(probe.hasPendingReviewTask(1L, 1L));
    }

    @Test
    void nullArgsShortCircuitWithoutQuery() {
        assertFalse(probe.hasPendingReviewTask(null, 1L));
        assertFalse(probe.hasPendingReviewTask(1L, null));
        verifyNoInteractions(taskMapper);
    }
}
