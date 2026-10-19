package org.dromara.test.adapter;

import com.smartdock.dcs.service.DcsNoticeService;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.DcsFileReview;
import com.smartdock.service.support.FileReviewNoticeEvent;
import org.dromara.web.adapter.FileReviewNoticeAdapter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 文件审核驳回通知适配器单测（批4 端口补齐，2026-10-18）。
 *
 * <p>契约：同步消费 FileReviewNoticeEvent（CAS 前快照，只读安全字段），
 * 通知上传人 FILE_REJECTED；无理由时内容不带理由段；缺租户/上传人丢弃留 warn。</p>
 */
@Tag("local")
class FileReviewNoticeAdapterTest {

    private final DcsNoticeService noticeService = mock(DcsNoticeService.class);
    private final FileReviewNoticeAdapter adapter = new FileReviewNoticeAdapter(noticeService);

    private static MockedStatic<TenantHelper> tenantPassThrough() {
        MockedStatic<TenantHelper> t = mockStatic(TenantHelper.class);
        t.when(() -> TenantHelper.dynamic(anyString(), any(Runnable.class)))
            .thenAnswer(inv -> {
                ((Runnable) inv.getArgument(1)).run();
                return null;
            });
        return t;
    }

    private static DcsFileReview review(String tenantId, Long uploaderId) {
        DcsFileReview r = new DcsFileReview();
        r.setReviewId(11L);
        r.setFileName("统计表.xlsx");
        r.setMissionId(22L);
        r.setUploaderId(uploaderId);
        r.setStatus("PENDING");
        r.setTenantId(tenantId);
        return r;
    }

    @Test
    void notifiesUploaderWithFileNameAndReason() {
        try (MockedStatic<TenantHelper> ignored = tenantPassThrough()) {
            adapter.onFileReviewRejected(new FileReviewNoticeEvent(review("000000", 9L), "含敏感词"));
        }
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(noticeService).notify(eq(9L), eq("FILE_REVIEW_REJECT"), title.capture(), content.capture(), eq(22L));
        assertTrue(title.getValue().contains("统计表.xlsx"));
        assertTrue(content.getValue().contains("含敏感词"));
        // 快照陷阱(dspro P2-3): 内容不得混入 CAS 前快照恒空的字段值(如 rejectReason 的 "null")
        assertFalse(content.getValue().contains("null"));
    }

    @Test
    void blankReasonOmitsReasonSegment() {
        try (MockedStatic<TenantHelper> ignored = tenantPassThrough()) {
            adapter.onFileReviewRejected(new FileReviewNoticeEvent(review("000000", 9L), " "));
        }
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(noticeService).notify(anyLong(), anyString(), anyString(), content.capture(), anyLong());
        assertTrue(content.getValue().contains("重新上传"));
        assertTrue(!content.getValue().contains("理由"));
    }

    @Test
    void nullFileNameFallsBackToUnnamed() {
        DcsFileReview r = review("000000", 9L);
        r.setFileName(null);
        try (MockedStatic<TenantHelper> ignored = tenantPassThrough()) {
            adapter.onFileReviewRejected(new FileReviewNoticeEvent(r, "x"));
        }
        verify(noticeService).notify(anyLong(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void missingUploaderDropsWithoutNotify() {
        adapter.onFileReviewRejected(new FileReviewNoticeEvent(review("000000", null), "x"));
        verify(noticeService, never()).notify(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void missingTenantDropsWithoutNotify() {
        adapter.onFileReviewRejected(new FileReviewNoticeEvent(review(" ", 9L), "x"));
        verify(noticeService, never()).notify(anyLong(), anyString(), anyString(), anyString(), any());
    }
}
