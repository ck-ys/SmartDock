package com.smartdock.fileReview;

import com.smartdock.service.support.DcsFileReviewGateImpl;
import com.smartdock.service.support.DcsFileReviewGateImpl.CurrentUser;
import com.smartdock.service.support.DcsFileReviewGateImpl.ReviewContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件审核放行矩阵纯函数单测（12b，8 场景——矩阵序的验收依据）。
 *
 * <p>纯函数 {@code DcsFileReviewGateImpl.allow} 不触库不触登录态，直接构造
 * ReviewContext/CurrentUser 断言矩阵每一条。</p>
 *
 * @author smartdock
 */
class FileReviewVisibilityUnitTest {

    /** 标准对照：普通上传人的待审单 + 陌生访客 → 拦（矩阵最后一行） */
    private static final ReviewContext PENDING_OTHERS = new ReviewContext("PENDING", 100L, 9001L);
    private static final CurrentUser STRANGER = new CurrentUser(999L, false, false, false);

    @Test
    @Tag("dev")
    void allowsWhenNoReviewOrApproved() {
        // 矩阵#2：无单（null）或已通过 → 放行（存量/AUTO 文件天然放行，无时间分界）
        assertThat(DcsFileReviewGateImplAllowProxy.allow(null, STRANGER, false)).isTrue();
        ReviewContext approved = new ReviewContext("APPROVED", 100L, 9001L);
        assertThat(DcsFileReviewGateImplAllowProxy.allow(approved, STRANGER, false)).isTrue();
    }

    @Test
    @Tag("dev")
    void allowsUploaderSelf() {
        // 矩阵#3：上传人本人可见自己的待审/被驳回文件（要看驳回理由才能重传）
        CurrentUser uploader = new CurrentUser(100L, false, false, false);
        assertThat(DcsFileReviewGateImplAllowProxy.allow(PENDING_OTHERS, uploader, false)).isTrue();
        ReviewContext rejected = new ReviewContext("REJECTED", 100L, null);
        assertThat(DcsFileReviewGateImplAllowProxy.allow(rejected, uploader, false)).isTrue();
    }

    @Test
    @Tag("dev")
    void blocksPendingForStranger() {
        // 矩阵#7：陌生人对 PENDING 单 → 拦
        assertThat(DcsFileReviewGateImplAllowProxy.allow(PENDING_OTHERS, STRANGER, false)).isFalse();
    }

    @Test
    @Tag("dev")
    void blocksRejectedForStranger() {
        // 矩阵#7：陌生人对 REJECTED 单 → 拦（驳回 ≠ 公开）
        ReviewContext rejected = new ReviewContext("REJECTED", 100L, null);
        assertThat(DcsFileReviewGateImplAllowProxy.allow(rejected, STRANGER, false)).isFalse();
    }

    @Test
    @Tag("dev")
    void allowsSuperOrTenantAdmin() {
        // 矩阵#4：超管/租户管理员豁免（需求明示）
        CurrentUser superAdmin = new CurrentUser(1L, true, false, false);
        assertThat(DcsFileReviewGateImplAllowProxy.allow(PENDING_OTHERS, superAdmin, false)).isTrue();
        CurrentUser tenantAdmin = new CurrentUser(2L, false, true, false);
        assertThat(DcsFileReviewGateImplAllowProxy.allow(PENDING_OTHERS, tenantAdmin, false)).isTrue();
    }

    @Test
    @Tag("dev")
    void allowsFileReviewerPermission() {
        // 矩阵#5：持有 dcs:fileReview:approve（file_reviewer 角色）→ 放行
        CurrentUser reviewer = new CurrentUser(555L, false, false, true);
        assertThat(DcsFileReviewGateImplAllowProxy.allow(PENDING_OTHERS, reviewer, false)).isTrue();
    }

    @Test
    @Tag("dev")
    void allowsActiveMissionReviewerOnlyWithFlag() {
        // 矩阵#6（Q2-A）：单带 missionId 且 isMissionReviewer=true → 放行；
        // 同一单对非任务审核人（flag=false）→ 拦
        assertThat(DcsFileReviewGateImplAllowProxy.allow(PENDING_OTHERS, STRANGER, true)).isTrue();
        assertThat(DcsFileReviewGateImplAllowProxy.allow(PENDING_OTHERS, STRANGER, false)).isFalse();
    }

    @Test
    @Tag("dev")
    void missionReviewerFlagIgnoredWhenNoMissionId() {
        // 矩阵#6 前置：missionId 为空的头像/聊天单，即使 flag=true 也不放行（防线：flag 只对带任务的单生效）
        ReviewContext avatarPending = new ReviewContext("PENDING", 100L, null);
        assertThat(DcsFileReviewGateImplAllowProxy.allow(avatarPending, STRANGER, true)).isFalse();
    }

    /** 静态纯函数直连（allow 是 DcsFileReviewGateImpl 的 public static 方法，此处包一层便于阅读） */
    private static final class DcsFileReviewGateImplAllowProxy {
        static boolean allow(ReviewContext review, CurrentUser cu, boolean isMissionReviewer) {
            return DcsFileReviewGateImpl.allow(review, cu, isMissionReviewer);
        }
    }
}
