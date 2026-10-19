package com.smartdock.profile.config;

import com.smartdock.profile.mapper.ProfileEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 事件保留期清理: 每日 03:30(GMT+8) 删过期事件, 单轮 1 万行分批。
 *
 * <p>快照是账本永不清理; 事件是原料可老化。多实例部署下靠单 JVM 内 AtomicBoolean
 * 防重入(跨实例重复删除幂等无害, 批1 不引入分布式锁)。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileRetentionJob {

    private static final int BATCH_LIMIT = 10_000;

    private final ProfileEventMapper eventMapper;

    /** 保留天数; 0 = 关闭清理 */
    @Value("${profile.event.retention-days:730}")
    private int retentionDays;

    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * cron 按容器时区(GMT+8 铁律)。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanup() {
        if (retentionDays <= 0) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Date before = new Date(System.currentTimeMillis() - retentionDays * 24L * 3600_0000L);
            long total = TenantHelper.ignore(() -> {
                long deleted = 0;
                int rows;
                int rounds = 0;
                do {
                    rows = eventMapper.deleteBefore(before, BATCH_LIMIT);
                    deleted += rows;
                } while (rows >= BATCH_LIMIT && ++rounds < 100); // dspro P2-4: 单次封顶 100 轮(百万行), 防失控
                return deleted;
            });
            if (total > 0) {
                log.info("[Profile] retention cleanup deleted {} events older than {} days", total, retentionDays);
            }
        } catch (Exception e) {
            log.error("[Profile] retention cleanup failed", e);
        } finally {
            running.set(false);
        }
    }
}
