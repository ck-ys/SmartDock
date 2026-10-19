package com.smartdock.service.support;

import lombok.Getter;
import com.smartdock.domain.DcsFileReview;

/**
 * 文件审核驳回域事件（模块化 2026-09-24 事件缝, 照 WalletAlertEvent 配方）。
 *
 * <p>由 file-security 模块内 {@code DcsFileReviewServiceImpl.reject} 在驳回事务内发布,
 * dcs 侧 {@code DcsFileReviewNoticePublisher} 以<b>同步 @EventListener</b> 消费后翻译成
 * {@code DcsNoticeEvent} 进通知管道。</p>
 *
 * <p><b>红线</b>: 消费端禁止改成 @TransactionalEventListener/@Async——DcsNoticeListener
 * 在 BEFORE_COMMIT 相位消费, 异步翻译 = 驳回通知静默丢失(合同#9 同事务必达语义)。</p>
 *
 * @author smartdock
 */
@Getter
public class FileReviewNoticeEvent {

    /**
     * 驳回单实体。注意: 是 CAS 前快照(status 仍为 PENDING、reviewTime/rejectReason 未回填)——
     * markRejected 是条件 UPDATE 不回写实体; 消费端只可读 fileName/missionId/tenantId/uploaderId/
     * ossId/reviewId 等与 CAS 无关的字段, 需要终态请重新查库(dspro 终审 P2 潜伏陷阱注记)。
     */
    private final DcsFileReview review;
    /** 驳回理由 */
    private final String reason;

    public FileReviewNoticeEvent(DcsFileReview review, String reason) {
        this.review = review;
        this.reason = reason;
    }
}
