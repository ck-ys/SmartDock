package com.smartdock.service.support;

import com.baomidou.lock.annotation.Lock4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.domain.DcsTenantWallet;
import com.smartdock.mapper.DcsTenantWalletMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 钱包低余额定时扫描。
 *
 * <p>每日 09:00 扫描所有 ACTIVE 钱包, 低于阈值的记录告警状态(按日历天判重)。
 * 告警经 WalletAlertEvent 域事件发布, dcs 侧 WalletAlertNoticeAdapter 同步翻译进
 * DcsNoticeEvent 通知管道(2026-09-24 模块化拆分)。</p>
 *
 * <p>批1 2026-09-25(拍板 Q4:B)两处加固:</p>
 * <ul>
 *   <li><b>启动补跑</b>——cron tick 落在重启窗口会被静默跳过且永不补(2026-09-24 08:59 重启
 *       真丢过一次告警)。启动就绪时若"已过 09:00"即补扫一次; 日历天判重天然防同日重复
 *       (09:00 tick 已跑过则状态行日期=今天, 补扫逐钱包秒级跳过);</li>
 *   <li><b>per-wallet 兜底</b>——单钱包通知失败(异常上抛自 tryAlert)只记堆栈不断当日扫描,
 *       其余钱包照常处理。</li>
 * </ul>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletAlertScheduler {

    /** 每日扫描时刻(北京时区小时数), 启动补跑以此为界 */
    private static final int DAILY_SCAN_HOUR = 9;
    /** 与启动参数 -Duser.timezone=GMT+8 同口径 */
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    /** 租户钱包表读写 Mapper（扫描活跃钱包） */
    private final DcsTenantWalletMapper walletMapper;
    /** 钱包告警业务服务（发送低余额告警，按日历天判重） */
    private final WalletAlertService walletAlertService;

    /**
     * 自身代理(@Lazy 破循环依赖): 启动补跑须走代理调 {@link #scanNow} 与 09:00 tick 共用
     * 同一把 @Lock4j 互斥(批2 拍板 Q6:A, 吸收 glm53 批1终审 P2-1 tick×补跑亚秒竞态);
     * 直构场景(单测无代理)为 null, 由调用处退化为直扫。
     */
    @Lazy
    @Autowired(required = false)
    private WalletAlertScheduler self;

    /**
     * 每日 09:00 扫描所有活跃钱包的低余额状态(批2 Q6:A: @Lock4j 跨节点互斥, 与启动补跑同键)。
     */
    @Scheduled(cron = "0 0 9 * * ?")
    @Lock4j(keys = {"'wallet-alert-daily-scan'"})
    public void scanLowBalance() {
        scanActiveWallets("每日定时");
    }

    /**
     * 启动补跑: 后端就绪时若已过每日扫描时刻(09:00)则补扫一次, 堵住"重启窗口错过 tick 且永不补"的丢告警;
     * 未到时刻不扫(交给当日的正常 tick)。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (!shouldCatchUpNow(LocalDateTime.now(BEIJING))) {
            return;
        }
        // 走代理复用 tick 的 @Lock4j 互斥; 直构(单测)无代理时退化直扫, 单进程串行天然安全
        if (self != null) {
            self.scanNow("启动补跑");
        } else {
            scanActiveWallets("启动补跑");
        }
    }

    /**
     * 扫描入口(批2 拍板 Q6:A): 与 09:00 tick 同键 @Lock4j, 跨节点/跨入口互斥。
     */
    @Lock4j(keys = {"'wallet-alert-daily-scan'"})
    public void scanNow(String trigger) {
        scanActiveWallets(trigger);
    }

    /** 补跑判定纯函数: 已过每日扫描时刻才补(未到时刻交给当日 tick, 避免提前扫描+到点重复扫两遍)。 */
    static boolean shouldCatchUpNow(LocalDateTime now) {
        return now.getHour() >= DAILY_SCAN_HOUR;
    }

    /**
     * 扫描所有活跃钱包: 低于阈值即告警; 单钱包失败只记堆栈, 不中断其余钱包。
     */
    private void scanActiveWallets(String trigger) {
        List<DcsTenantWallet> wallets = walletMapper.selectList(new LambdaQueryWrapper<DcsTenantWallet>()
            .eq(DcsTenantWallet::getStatus, "ACTIVE")
            .eq(DcsTenantWallet::getDelFlag, "0"));
        int alerted = 0;
        for (DcsTenantWallet wallet : wallets) {
            try {
                BigDecimal available = wallet.getBalanceAmount().subtract(
                    wallet.getFrozenAmount() == null ? BigDecimal.ZERO : wallet.getFrozenAmount());
                BigDecimal threshold = wallet.getLowBalanceThreshold() == null
                    ? BigDecimal.ONE : wallet.getLowBalanceThreshold();
                if (available.compareTo(threshold) <= 0) {
                    String alertType = available.compareTo(BigDecimal.ZERO) <= 0 ? "EXHAUSTED" : "LOW_BALANCE";
                    boolean sent = walletAlertService.tryAlert(wallet, alertType, threshold);
                    if (sent) {
                        alerted++;
                    }
                }
            } catch (Exception e) {
                // per-wallet 兜底: 通知失败异常自 tryAlert 上抛(当日配额未消耗, 次日/下次补跑重试), 堆栈在此记录
                log.error("[WalletAlert] 钱包 {} 告警处理失败({}), 跳过继续", wallet.getWalletId(), trigger, e);
            }
        }
        if (alerted > 0) {
            log.info("[WalletAlert] 扫描完成({}), 发送 {} 条低余额告警", trigger, alerted);
        }
    }
}
