package org.dromara.web.adapter;

import com.smartdock.dcs.service.DcsNoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.service.support.WalletAlertEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 钱包告警域事件 → 微核心站内通知的翻译适配器（批4 端口补齐，2026-10-18）。
 *
 * <p>旧仓消费者随 com.smartdock 退役。ai-billing 只发布 {@link WalletAlertEvent}
 * （低余额/耗尽/对账差异/订阅到期族），本适配器在 admin 组合根把它翻译成
 * {@code DcsNoticeService.notify}，余额告警链路恢复闭环。</p>
 *
 * <p><b>相位红线（继承旧合同#9）</b>：必须同步 {@code @EventListener}（同线程同事务），
 * 禁止 @Async / @TransactionalEventListener——异步翻译 = 调度事务已提交、告警静默丢失。
 * 通知失败让异常上抛：调度器按钱包兜底记堆栈、告警状态事务回滚不消耗，次日重扫补达
 * （fail-fast 有意为之，弃计划书初版的 fail-soft——部分送达+状态已耗的中间态更糟）。</p>
 *
 * <p><b>频次闸（批4 回修，三审并案 P1）</b>：businessDedupKey 逐字透传给
 * notifyIdem——积分告警族（周/月/一次频次）与对账告警无状态表，键就是唯一节流器，
 * 丢弃即日轰。低余额链键为 null，走源侧 dcs_wallet_alert_state 日历天判重，不受影响。</p>
 *
 * <p>字段口径：noticeType/title/content 逐字透传；refUrl/extra 丢弃（微核心通知模型极简，
 * 无跳转列，登记批5 通知中心接线时评估）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletAlertNoticeAdapter {

    private final DcsNoticeService noticeService;

    @EventListener
    public void onWalletAlert(WalletAlertEvent event) {
        if (event.getTenantId() == null || event.getTenantId().isBlank()) {
            log.warn("[WalletAlertNotice] 无租户上下文的告警被丢弃 type={} title={}",
                event.getNoticeType(), event.getTitle());
            return;
        }
        if (event.getRecipientUserIds() == null || event.getRecipientUserIds().isEmpty()) {
            log.warn("[WalletAlertNotice] 告警无收件人被丢弃 tenant={} type={}",
                event.getTenantId(), event.getNoticeType());
            return;
        }
        int delivered = 0;
        for (Long userId : event.getRecipientUserIds()) {
            if (userId == null) {
                continue;
            }
            boolean inserted = TenantScopes.callInTenant(event.getTenantId(), () ->
                noticeService.notifyIdem(userId, event.getNoticeType(), event.getTitle(),
                    event.getContent(), null, event.getBusinessDedupKey()));
            if (inserted) {
                delivered++;
            }
        }
        log.info("[WalletAlertNotice] tenant={} type={} 收件 {} 人, 本次新落通知 {} 条(其余为幂等键跳过)",
            event.getTenantId(), event.getNoticeType(), event.getRecipientUserIds().size(), delivered);
    }
}
