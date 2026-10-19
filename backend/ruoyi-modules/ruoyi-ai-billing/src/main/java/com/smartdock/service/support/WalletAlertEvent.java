package com.smartdock.service.support;

import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 钱包告警域事件(模块化拆分 2026-09-24): 低余额/额度耗尽时由 WalletAlertService 在事务内发布。
 *
 * <p>设计约束:
 * <ul>
 *   <li><b>不继承 DcsNoticeEvent</b>——ai-billing 模块禁止反向依赖 dcs 的通知事件,
 *       且继承会导致 dcs 侧 DcsNoticeListener 按类型兼容匹配到两次(outbox 无业务级去重 = 管理员收双份);</li>
 *   <li>dcs 侧 {@code WalletAlertNoticeAdapter} 以<b>同步 @EventListener</b> 在同事务内翻译成
 *       DcsNoticeEvent 进通知管道(BEFORE_COMMIT 落 outbox), 语义与拆分前完全等价;</li>
 *   <li>noticeType 字面量(WALLET_LOW_BALANCE/WALLET_EXHAUSTED)被 DcsNoticeMapper.xml 与
 *       DcsNoticeServiceImpl 硬编码过滤, <b>禁止改字</b>, 否则告警从"风险分组"页静默消失。</li>
 * </ul></p>
 *
 * @author smartdock
 */
@Getter
public class WalletAlertEvent {

    /** 租户 ID */
    private final String tenantId;
    /** 收件人(租户管理员 userId 列表) */
    private final List<Long> recipientUserIds;
    /** 告警类型字面量: WALLET_LOW_BALANCE / WALLET_EXHAUSTED(硬编码口径, 禁改) */
    private final String noticeType;
    /** 通知标题 */
    private final String title;
    /** 通知内容 */
    private final String content;
    /** 跳转链接(/tenant/tenant-wallet) */
    private final String refUrl;
    /** 扩展信息(walletId/available/threshold) */
    private final Map<String, Object> extra;
    /**
     * outbox 业务级幂等键(批2 2026-09-25, 拍板 Q6:A): null=legacy 键(outboxId 唯一, 低余额链
     * 保持不动——DB 日历天判重已兜); 非空则同键重发幂等重放(outbox 按键去重不双发), 对账告警用
     * walletReconcile:{tenantId}:{yyyyMMdd} 实现"每租户每日一告"。
     */
    private final String businessDedupKey;

    public WalletAlertEvent(String tenantId, List<Long> recipientUserIds, String noticeType,
                            String title, String content, String refUrl, Map<String, Object> extra) {
        this(tenantId, recipientUserIds, noticeType, title, content, refUrl, extra, null);
    }

    public WalletAlertEvent(String tenantId, List<Long> recipientUserIds, String noticeType,
                            String title, String content, String refUrl, Map<String, Object> extra,
                            String businessDedupKey) {
        this.tenantId = tenantId;
        this.recipientUserIds = recipientUserIds;
        this.noticeType = noticeType;
        this.title = title;
        this.content = content;
        this.refUrl = refUrl;
        this.extra = extra;
        this.businessDedupKey = businessDedupKey;
    }
}
