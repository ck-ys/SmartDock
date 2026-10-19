package com.smartdock.service.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.domain.DcsTenantWallet;
import com.smartdock.domain.DcsWalletAlertState;
import com.smartdock.mapper.DcsWalletAlertStateMapper;
import com.smartdock.mapper.WalletAlertRecipientMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 钱包告警服务: 按日历天判重防重复 + WalletAlertEvent 域事件发布(2026-09-24 模块化拆分:
 * dcs 侧 WalletAlertNoticeAdapter 以同步 @EventListener 翻译成 DcsNoticeEvent 进通知管道;
 * 批1 2026-09-25 拍板 Q4:B——24h 毫秒冷却改日历天判重+通知成功后才消耗当日配额)。
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletAlertService {

    /** 告警判重时区(与启动参数 -Duser.timezone=GMT+8 同口径) */
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    /** 钱包告警状态 Mapper(北京日历天判重幂等) */
    private final DcsWalletAlertStateMapper alertStateMapper;
    /** Spring 事件发布器(发低余额通知) */
    private final ApplicationEventPublisher eventPublisher;
    /** 告警收件人解析 Mapper(批2 Q7-⑨: sys_user_role 真角色解析, 替代 userId≤2 约定) */
    private final WalletAlertRecipientMapper recipientMapper;

    /**
     * 尝试发送钱包告警(按日历天判重, 幂等)。
     *
     * <p>批1 2026-09-25 两处语义修正(拍板 Q4:B):</p>
     * <ul>
     *   <li><b>日历天判重替代 24h 毫秒冷却</b>——旧"now-last&lt;24h 跳过"与每日 09:00 固定 tick 相撞,
     *       毫秒级抖动随机吞掉某天告警(捕获点在 tick 瞬间, 次日 tick 与 24h 边界比毫秒)。改
     *       "上次告警日期≠今天才发"后每天最多一条, 竞态被构造性消灭;</li>
     *   <li><b>状态推进移到通知发布成功之后</b>——旧序先落 lastAlertTime 再发通知且内层吞异常,
     *       通知失败也吃满冷却(最坏 48h 静默)。现序: 先发事件(异常上抛→本事务回滚→状态未消耗,
     *       由调度器 per-wallet 兜底接住), 成功后才 upsert 状态。</li>
     * </ul>
     *
     * @param wallet     钱包
     * @param alertType  LOW_BALANCE / EXHAUSTED
     * @param threshold  阈值
     * @return true=今日配额已消耗(发了通知, 或无收件人静默跳过); false=今天已发过
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean tryAlert(DcsTenantWallet wallet, String alertType, BigDecimal threshold) {
        DcsWalletAlertState existing = alertStateMapper.selectOne(new LambdaQueryWrapper<DcsWalletAlertState>()
            .eq(DcsWalletAlertState::getWalletId, wallet.getWalletId())
            .eq(DcsWalletAlertState::getAlertType, alertType)
            .eq(DcsWalletAlertState::getDelFlag, "0"));
        LocalDate today = LocalDate.now(BEIJING);
        if (existing != null && existing.getLastAlertTime() != null
            && LocalDate.ofInstant(existing.getLastAlertTime().toInstant(), BEIJING).equals(today)) {
            return false; // 今天已发过(日历天判重)
        }
        // 先发通知: 失败异常上抛→事务回滚→当日配额未消耗, 调度器 per-wallet 兜底记录后继续
        sendWalletNotice(wallet, alertType, threshold);
        Date now = new Date();
        if (existing == null) {
            DcsWalletAlertState state = new DcsWalletAlertState();
            state.setTenantId(wallet.getTenantId());
            state.setWalletId(wallet.getWalletId());
            state.setAlertType(alertType);
            state.setLastAlertTime(now);
            state.setThresholdAmount(threshold);
            alertStateMapper.insert(state);
        } else {
            existing.setLastAlertTime(now);
            existing.setThresholdAmount(threshold);
            alertStateMapper.updateById(existing);
        }
        return true;
    }

    /**
     * 向租户管理员发送低余额/耗尽通知。
     *
     * <p>批1 2026-09-25: 去掉内层 try-catch 吞异常(配合 tryAlert"先发后落状态"——失败让事务回滚,
     * 当日配额不消耗, 堆栈由调度器 per-wallet 兜底记录)。</p>
     *
     * <p>批2 2026-09-25(拍板 Q7-⑨): 收件人改 sys_user_role 真角色解析; 无 admin 角色用户的租户
     * 降级平台超管接收(通知挂 000000+内容标注真实租户), 原"无人接收静默消耗配额"的黑洞关闭。</p>
     */
    private void sendWalletNotice(DcsTenantWallet wallet, String alertType, BigDecimal threshold) {
        AlertRecipients recipients = resolveRecipients(wallet.getTenantId());
        BigDecimal available = wallet.getBalanceAmount().subtract(
            wallet.getFrozenAmount() == null ? BigDecimal.ZERO : wallet.getFrozenAmount())
            .setScale(4, RoundingMode.HALF_UP);
        String title = "EXHAUSTED".equals(alertType) ? "钱包额度已耗尽" : "钱包余额不足提醒";
        String body = "EXHAUSTED".equals(alertType)
            ? "您的钱包额度已耗尽(可用余额: " + available + " 元), AI 付费服务将按兜底策略降级或阻塞, 请及时充值。"
            : "您的钱包余额已低于阈值(" + threshold + " 元), 当前可用: " + available + " 元, 请及时充值。";
        String content = recipients.degraded() ? "【租户 " + wallet.getTenantId() + "】" + body : body;
        // 低余额链保持 legacy 去重键(null)不动: DB 日历天判重已兜, 拍板 Q6:A
        WalletAlertEvent event = new WalletAlertEvent(
            recipients.noticeTenantId(), recipients.userIds(),
            "WALLET_" + alertType,
            title, content, "/tenant/tenant-wallet",
            Map.of("walletId", wallet.getWalletId(), "available", available, "threshold", threshold));
        eventPublisher.publishEvent(event);
        log.info("[WalletAlert] 租户={} 钱包={} 发送 {} 通知给 {} 个管理员",
            wallet.getTenantId(), wallet.getWalletId(), alertType, recipients.userIds().size());
    }

    /**
     * 发布钱包对账差异告警(批C 2026-09-24): 供 {@link WalletReconcileJob} 在扫出白名单外新差异后调用。
     *
     * <p>⚠️ 事务边界红线: 必须在事务内发布——dcs 侧 WalletAlertNoticeAdapter 同步翻译成
     * DcsNoticeEvent 后由 BEFORE_COMMIT 相位监听落 outbox, 无事务 = 通知静默丢失
     * (本方法自带 @Transactional 即为此而设, 调用方勿在事务外裸调)。</p>
     *
     * @param tenantId  差异所属租户
     * @param title     通知标题
     * @param content   差异明细(多行文本)
     * @param extra     扩展信息(差异计数等)
     */
    @Transactional(rollbackFor = Exception.class)
    public void publishReconcileAlert(String tenantId, String title, String content, Map<String, Object> extra) {
        try {
            AlertRecipients recipients = resolveRecipients(tenantId);
            String noticeContent = recipients.degraded() ? "【租户 " + tenantId + "】" + content : content;
            // 每租户每日一告(拍板 Q6:A): 同键重发由 outbox 幂等重放, 不双发; 键用真实业务租户(降级收件不影响)
            String dedupKey = "walletReconcile:" + tenantId + ":"
                + LocalDate.now(BEIJING).format(DateTimeFormatter.BASIC_ISO_DATE);
            eventPublisher.publishEvent(new WalletAlertEvent(recipients.noticeTenantId(), recipients.userIds(),
                "WALLET_RECONCILE_MISMATCH", title, noticeContent, "/tenant/tenant-wallet", extra, dedupKey));
            log.info("[WalletReconcile] 租户 {} 发送钱包对账差异告警给 {} 个管理员", tenantId, recipients.userIds().size());
        } catch (Exception e) {
            // glm53 P2-2: 带异常对象保堆栈; 对账差异未处理次晚会重告, 单次丢失可容忍
            log.warn("[WalletReconcile] 对账告警发送失败", e);
        }
    }

    /**
     * 发布积分侧告警(AI 缺口批 Q1/Q2, 2026-10-14): 套餐到期/周池用完/加油包将罀。
     *
     * <p>与 {@link #publishReconcileAlert} 同配方: 自带 @Transactional(outbox BEFORE_COMMIT
     * 落库红线, 无事务=通知静默丢失), dedupKey 必填(outbox 业务级幂等, 键设计即告警频次),
     * 收件人走 {@link #resolveRecipients} 同款降级(无 admin 角色租户→平台超管+租户前缀)。
     * 内部吞异常只告警——单条告警失败不拦调度器当日扫描。</p>
     *
     * @param tenantId 业务真实租户(降级收件不影响键与内容标注)
     * @param noticeType CREDIT_SUBSCRIPTION_EXPIRING / CREDIT_WEEK_EXHAUSTED / CREDIT_TANK_LOW
     * @param dedupKey outbox 幂等键(调用方按"每订阅一次/每周/每月"口径构造)
     */
    @Transactional(rollbackFor = Exception.class)
    public void publishCreditAlert(String tenantId, String noticeType, String title, String content,
                                   String refUrl, Map<String, Object> extra, String dedupKey) {
        try {
            AlertRecipients recipients = resolveRecipients(tenantId);
            String noticeContent = recipients.degraded() ? "【租户 " + tenantId + "】" + content : content;
            eventPublisher.publishEvent(new WalletAlertEvent(recipients.noticeTenantId(), recipients.userIds(),
                noticeType, title, noticeContent, refUrl, extra, dedupKey));
            log.info("[CreditAlert] 租户 {} 发送 {} 给 {} 个管理员", tenantId, noticeType, recipients.userIds().size());
        } catch (Exception e) {
            // 与对账告警同口径: 带堆栈, 单条失败不拦扫描(次日/下次扫描键不变不重发, 由键频次兜底)
            log.warn("[CreditAlert] 告警发送失败 tenant={} type={}", tenantId, noticeType, e);
        }
    }

    /**
     * 告警收件人解析结果: 通知挂靠租户(降级时切 000000——超管按 000000 上下文查询, 挂业务租户他看不见)。
     */
    private record AlertRecipients(List<Long> userIds, String noticeTenantId, boolean degraded) {
    }

    /**
     * 解析租户告警收件人(批2 拍板 Q7-⑨ 根治): sys_user_role 关联真角色(role_key='admin'),
     * userId≤2 约定退役; 无 admin 角色用户的租户(如 900001 仅普通用户)降级平台超管(userId=1)接收,
     * 通知挂 000000 租户、内容前缀标注真实租户——原"无人接收静默消耗配额"的低余额告警黑洞随之关闭。
     */
    private AlertRecipients resolveRecipients(String tenantId) {
        List<Long> admins = recipientMapper.selectTenantAdminUserIds(tenantId);
        if (!admins.isEmpty()) {
            return new AlertRecipients(admins, tenantId, false);
        }
        log.warn("[WalletAlert] 租户 {} 无 admin 角色用户, 降级平台超管接收", tenantId);
        return new AlertRecipients(List.of(1L), "000000", true);
    }
}

