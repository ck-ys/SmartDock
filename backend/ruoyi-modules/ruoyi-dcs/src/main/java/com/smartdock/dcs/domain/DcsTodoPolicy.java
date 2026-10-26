package com.smartdock.dcs.domain;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 待办口径(U-03 口径对齐): 收集中的任务, 且本人无提交或最新提交被退回, 才算「我的待办」。
 *
 * <p>排除口径按「存在 PENDING_AUDIT/APPROVED 提交行」而非逐任务取最新版:
 * submit() 只允许在 latest=null/REJECTED 时新建版本(DcsSubmissionService.submit 守卫),
 * 故 PENDING_AUDIT/APPROVED 行必为本人该任务的最高版本行——
 * 「存在 PA/APPROVED 行」⇔「最新提交为 PA/APPROVED」, 两口径等价。
 * 如此可整体下推为一次 IN 查询 + NOT IN 过滤, 让分页 total 与行列表同口径,
 * 不再出现「计数说有 N 条、列表却是空态」的矛盾。</p>
 *
 * @author smartdock
 */
public final class DcsTodoPolicy {

    private DcsTodoPolicy() {
    }

    /** 该任务对本人是否算待办: 任务收集中, 且本人无提交(null)或最新提交被退回(REJECTED) */
    public static boolean isTodo(String taskStatus, String myLatestSubmissionStatus) {
        if (!DcsTask.STATUS_COLLECTING.equals(taskStatus)) {
            return false;
        }
        return myLatestSubmissionStatus == null || DcsSubmission.STATUS_REJECTED.equals(myLatestSubmissionStatus);
    }

    /** 从本人全部提交行算出「不再是待办」的任务 id 集合(在途 PENDING_AUDIT / 已通过 APPROVED) */
    public static Set<Long> notTodoTaskIds(Collection<DcsSubmission> mine) {
        Set<Long> ids = new HashSet<>();
        for (DcsSubmission s : mine) {
            if (DcsSubmission.STATUS_PENDING_AUDIT.equals(s.getStatus())
                || DcsSubmission.STATUS_APPROVED.equals(s.getStatus())) {
                ids.add(s.getTaskId());
            }
        }
        return ids;
    }
}
