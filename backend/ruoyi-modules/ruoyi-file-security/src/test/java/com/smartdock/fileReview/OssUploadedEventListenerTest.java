package com.smartdock.fileReview;

import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.domain.DcsFileReview;
import com.smartdock.domain.DcsFileReviewConfig;
import com.smartdock.listener.OssUploadedEventListener;
import com.smartdock.mapper.DcsFileReviewMapper;
import com.smartdock.service.IDcsFileReviewConfigService;
import org.dromara.system.event.OssFileUploadedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传建单监听器单测（批4 端口补齐重建，2026-10-18；三审 dsflash P0 回修的回归防线）。
 *
 * <p>锁定判定序：⓪guard 强制进审 > ⓪.5 FEEDBACK/ASSISTANT 豁免留痕 > ①开关 >
 * ②管理员豁免 > ③类目勾选 > ④建单 PENDING。任何分支回退（如按 bizSource 免审、
 * guard 信号丢失）都会在此露馅。</p>
 */
@Tag("dev")
class OssUploadedEventListenerTest {

    private final IDcsFileReviewConfigService configService = mock(IDcsFileReviewConfigService.class);
    private final DcsFileReviewMapper reviewMapper = mock(DcsFileReviewMapper.class);
    private final OssUploadedEventListener listener = new OssUploadedEventListener(configService, reviewMapper);

    private static OssFileUploadedEvent event(String suffix, String bizSource, String guardReason) {
        OssFileUploadedEvent e = new OssFileUploadedEvent("报表.xlsx", suffix, "application/octet-stream",
            1024L, bizSource, 22L, 9L);
        e.setGuardReason(guardReason);
        return e;
    }

    private static MockedStatic<LoginHelper> plainUser() {
        MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class);
        login.when(LoginHelper::isSuperAdmin).thenReturn(false);
        login.when(LoginHelper::isTenantAdmin).thenReturn(false);
        login.when(LoginHelper::getLoginUser).thenReturn(new LoginUser());
        return login;
    }

    @Test
    void guardReasonForcesPendingEvenWhenDisabledOrAdmin() {
        when(configService.getEffective()).thenThrow(new IllegalStateException("不该走到配置查询"));
        try (MockedStatic<LoginHelper> ignored = plainUser()) {
            OssFileUploadedEvent e = event(".xlsx", null, "MAGIC_MISMATCH");
            listener.onUploaded(e);
            assertEquals("PENDING", e.getReviewStatus());
        }
        ArgumentCaptor<DcsFileReview> cap = ArgumentCaptor.forClass(DcsFileReview.class);
        verify(reviewMapper).insert(cap.capture());
        assertEquals("PENDING", cap.getValue().getStatus());
        assertEquals("MAGIC_MISMATCH", cap.getValue().getGuardReason());
        assertEquals(Long.valueOf(9L), cap.getValue().getUploaderId());
    }

    @Test
    void feedbackScreenshotCreatesExemptRowAndSkipsHumanReview() {
        try (MockedStatic<LoginHelper> ignored = plainUser()) {
            OssFileUploadedEvent e = event(".png", "FEEDBACK", null);
            listener.onUploaded(e);
            assertEquals("SKIPPED", e.getReviewStatus());
        }
        ArgumentCaptor<DcsFileReview> cap = ArgumentCaptor.forClass(DcsFileReview.class);
        verify(reviewMapper).insert(cap.capture());
        assertEquals("EXEMPT", cap.getValue().getStatus());
        assertNull(cap.getValue().getGuardReason());
    }

    @Test
    void disabledConfigSkipsWithoutRow() {
        DcsFileReviewConfig cfg = new DcsFileReviewConfig();
        cfg.setEnabled("0");
        cfg.setCategories("DOCUMENT");
        when(configService.getEffective()).thenReturn(cfg);
        try (MockedStatic<LoginHelper> ignored = plainUser()) {
            OssFileUploadedEvent e = event(".xlsx", null, null);
            listener.onUploaded(e);
            assertEquals("SKIPPED", e.getReviewStatus());
        }
        verify(reviewMapper, never()).insert(any(DcsFileReview.class));
    }

    @Test
    void adminUploadSkipsWithoutRow() {
        DcsFileReviewConfig cfg = new DcsFileReviewConfig();
        cfg.setEnabled("1");
        cfg.setCategories("DOCUMENT");
        when(configService.getEffective()).thenReturn(cfg);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isSuperAdmin).thenReturn(true);
            login.when(LoginHelper::isTenantAdmin).thenReturn(false);
            OssFileUploadedEvent e = event(".xlsx", null, null);
            listener.onUploaded(e);
            assertEquals("SKIPPED", e.getReviewStatus());
        }
        verify(reviewMapper, never()).insert(any(DcsFileReview.class));
    }

    @Test
    void unselectedCategorySkipsWithoutRow() {
        DcsFileReviewConfig cfg = new DcsFileReviewConfig();
        cfg.setEnabled("1");
        cfg.setCategories("IMAGE,VIDEO");
        when(configService.getEffective()).thenReturn(cfg);
        try (MockedStatic<LoginHelper> ignored = plainUser()) {
            OssFileUploadedEvent e = event(".xlsx", null, null);
            listener.onUploaded(e);
            assertEquals("SKIPPED", e.getReviewStatus());
        }
        verify(reviewMapper, never()).insert(any(DcsFileReview.class));
    }

    @Test
    void selectedCategoryCreatesPendingRow() {
        DcsFileReviewConfig cfg = new DcsFileReviewConfig();
        cfg.setEnabled("1");
        cfg.setCategories("IMAGE,DOCUMENT");
        when(configService.getEffective()).thenReturn(cfg);
        try (MockedStatic<LoginHelper> ignored = plainUser()) {
            OssFileUploadedEvent e = event(".xlsx", "MISSION_FORM", null);
            listener.onUploaded(e);
            assertEquals("PENDING", e.getReviewStatus());
        }
        ArgumentCaptor<DcsFileReview> cap = ArgumentCaptor.forClass(DcsFileReview.class);
        verify(reviewMapper).insert(cap.capture());
        assertEquals("PENDING", cap.getValue().getStatus());
        assertEquals("DOCUMENT", cap.getValue().getFileCategory());
        assertEquals("MISSION_FORM", cap.getValue().getBizSource());
    }
}
