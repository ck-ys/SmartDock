package com.smartdock.profile.engine;

/**
 * 批10 Q1: 分布式锁键名与租约定一的唯一出处。
 *
 * <p>三处原 JVM 内存态(重建互斥 ReentrantLock / 汇总去抖 running / 单主体重算 running)
 * 统一换 Redisson RLock, 单实例行为不变, 第二个实例自动去重。
 * 租期刻意给显式值不用 watchdog: 持锁进程崩溃后锁最迟在租期内自动过期,
 * 另一实例可接管(拍板页 A 案"锁自动过期兜底")。租期远大于演示量级下的
 * 实际执行时长, 正常执行不会中途失锁。</p>
 *
 * @author smartdock
 */
public final class ProfileLocks {

    /** 会话/日汇总重建互斥(手动 rebuild + 自动 rebuildRecent 共用一把锁)。 */
    public static final long REBUILD_LEASE_MINUTES = 10;

    /** 单主体自动重算(评分+模型增量): 短平快, 5 分钟足够。 */
    public static final long RECALC_LEASE_MINUTES = 5;

    /** 模型全量运行(publish/rollback/replay 复用): 长任务给宽裕。 */
    public static final long MODEL_RUN_LEASE_MINUTES = 30;

    /** 触达批次重试互斥。 */
    public static final long REACH_RETRY_LEASE_MINUTES = 10;

    private ProfileLocks() {
    }

    /** 会话重建互斥: 同(租户,业务源)同一时间只有一个重建者。 */
    public static String rebuild(String tenantId, String source) {
        return "smartdock:analytics:rebuild:" + tenantId + ":" + source;
    }

    /** 单主体自动重算互斥: 跨实例同主体只算一次。 */
    public static String recalcSubject(String tenantId, String source, String subjectType, String subjectId) {
        return "smartdock:profile:recalc:" + tenantId + ":" + source + ":"
            + subjectType + ":" + subjectId;
    }

    /** 模型全量运行互斥: publish / rollback / 直接 run / 回放重算共用。 */
    public static String modelRun(Long modelId) {
        return "smartdock:profile:model-run:" + modelId;
    }

    /** 触达批次重试互斥: 并发重试不双发不覆盖计数。 */
    public static String reachRetry(Long reachId) {
        return "smartdock:profile:reach-retry:" + reachId;
    }

    /** 回放任务互斥: 回放与自动重建/其他回放互斥(拍板 C.6: 回放须持 Q1 分布式锁)。 */
    public static String replay(String tenantId, String source) {
        return "smartdock:analytics:replay:" + tenantId + ":" + source;
    }
}
