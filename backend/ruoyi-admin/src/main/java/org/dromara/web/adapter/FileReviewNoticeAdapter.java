package org.dromara.web.adapter;

import com.smartdock.dcs.service.DcsNoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import com.smartdock.domain.DcsFileReview;
import com.smartdock.service.support.FileReviewNoticeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 文件审核驳回域事件 → 微核心站内通知的翻译适配器（批4 端口补齐，2026-10-18）。
 *
 * <p>旧仓消费者随 com.smartdock 退役。file-security 在驳回事务内发布
 * {@link FileReviewNoticeEvent}，本适配器在 admin 组合根把它翻译成
 * {@code DcsNoticeService.notify}，驳回通知恢复闭环（上传人收到 FILE_REVIEW_REJECT）。</p>
 *
 * <p><b>相位红线（继承旧合同#9）</b>：同步 {@code @EventListener} 同事务必达；
 * 通知失败异常上抛 → 驳回事务回滚 → 审核人重试，不允许「驳回了但没人知道」。</p>
 *
 * <p><b>通知类型字面量（批4 回修，dspro P1-1）</b>：必须用 FILE_REVIEW_REJECT——
 * 前端通知词表 noticePresentation.ts 只认它（ACTIONABLE·高优·去重新上传）；
 * FILE_REJECTED 是风控<b>事件</b>类型（dcs_risk_event），两个命名空间严禁混用，
 * 混用会让驳回通知静默降级成「系统动态」。</p>
 *
 * <p><b>快照陷阱（dspro 终审注记继承）</b>：事件携带的是 CAS 前快照——status 仍为
 * PENDING、reviewTime/rejectReason 未回填，只读 fileName/missionId/tenantId/
 * uploaderId 等与 CAS 无关的字段。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileReviewNoticeAdapter {

    /** 通知类型字面量：与旧仓/前端 noticePresentation 一致（ACTIONABLE·高优·去重新上传） */
    static final String NOTICE_TYPE_FILE_REVIEW_REJECT = "FILE_REVIEW_REJECT";

    private final DcsNoticeService noticeService;

    @EventListener
    public void onFileReviewRejected(FileReviewNoticeEvent event) {
        DcsFileReview review = event.getReview();
        if (review.getTenantId() == null || review.getTenantId().isBlank()
            || review.getUploaderId() == null) {
            log.warn("[FileReviewNotice] 驳回事件缺租户/上传人, 通知被丢弃 reviewId={}",
                review.getReviewId());
            return;
        }
        String fileName = StringUtils.isBlank(review.getFileName()) ? "未命名文件" : review.getFileName();
        String content = "你上传的文件「" + fileName + "」审核未通过"
            + (StringUtils.isBlank(event.getReason()) ? "" : "，理由: " + event.getReason())
            + "，请修改后重新上传。";
        TenantScopes.runInTenant(review.getTenantId(), () ->
            noticeService.notify(review.getUploaderId(), NOTICE_TYPE_FILE_REVIEW_REJECT,
                "文件审核驳回: " + fileName, content, review.getMissionId()));
        log.info("[FileReviewNotice] tenant={} review={} 驳回已通知上传人 {}",
            review.getTenantId(), review.getReviewId(), review.getUploaderId());
    }
}
