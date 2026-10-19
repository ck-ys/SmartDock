package org.dromara.test.assistant;

import org.dromara.assistant.limiter.AssistantAdmissionGate;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批D D2 准入闸单测(2026-10-05, 真 Redis 滑窗):
 *
 * <p>判据: ①租户超限拦新人; ②全站超限拦新人; ③窗口过期自动释放(滑窗而非固定窗);
 * ④在册成员续期不占新名额(同一用户反复请求不受限); ⑤50 并发下恰 N 人通过(原子 Lua 无竞态);
 * ⑥sys_config 默认参数路径可用(读全局种子, 同用户重复放行)。</p>
 *
 * <p>隔离: 独立测试租户 999998/999997, @AfterEach 清 ZSET 键(global 键整删, 生产请求会重新入册,
 * 无损)。</p>
 */
@Tag("p0probe")
@SpringBootTest
class D2AdmissionTest {

    private static final String T1 = "999998";
    private static final long WINDOW_MS = 400;

    @Autowired
    AssistantAdmissionGate gate;

    @AfterEach
    void cleanup() {
        RedisUtils.getClient().getKeys().delete(
            "dcs:assistant:admit:tenant:" + T1,
            "dcs:assistant:admit:tenant:999997",
            "dcs:assistant:admit:global");
    }

    /** ①租户超限: 上限 2, 第 3 个新用户被拦, 话术统一 */
    @Test
    void tenantLimitBlocksNewUser() {
        assertDoesNotThrow(() -> gate.admit(T1, 1L, 2, 100, WINDOW_MS));
        assertDoesNotThrow(() -> gate.admit(T1, 2L, 2, 100, WINDOW_MS));
        ServiceException ex = assertThrows(ServiceException.class, () -> gate.admit(T1, 3L, 2, 100, WINDOW_MS));
        assertTrue(ex.getMessage().contains("人数较多"), "超限话术: " + ex.getMessage());
    }

    /** ②全站超限: 两租户各 1 人后全站=2, 第三租户新人被拦 */
    @Test
    void globalLimitBlocksAcrossTenants() {
        assertDoesNotThrow(() -> gate.admit(T1, 11L, 10, 2, WINDOW_MS));
        assertDoesNotThrow(() -> gate.admit("999997", 12L, 10, 2, WINDOW_MS));
        assertThrows(ServiceException.class, () -> gate.admit("999996", 13L, 10, 2, WINDOW_MS));
    }

    /** ③滑窗过期释放: 过窗后原名额蒸发, 新用户可入 */
    @Test
    void windowExpiryReleasesSlots() throws Exception {
        gate.admit(T1, 21L, 1, 100, 300);
        assertThrows(ServiceException.class, () -> gate.admit(T1, 22L, 1, 100, 300));
        Thread.sleep(450);
        assertDoesNotThrow(() -> gate.admit(T1, 22L, 1, 100, 300), "过窗后名额释放");
    }

    /** ④在册成员续期: 上限 1, 同一用户二次请求刷新而非占新名额; 别人仍被拦 */
    @Test
    void existingMemberRefreshesNotRecounts() {
        gate.admit(T1, 31L, 1, 100, WINDOW_MS);
        assertDoesNotThrow(() -> gate.admit(T1, 31L, 1, 100, WINDOW_MS), "在册成员续期放行");
        assertThrows(ServiceException.class, () -> gate.admit(T1, 32L, 1, 100, WINDOW_MS), "续期不占新名额");
    }

    /** ⑤50 并发 30 上限: 恰 30 过 20 拦(原子判限, 无超发) */
    @Test
    void fiftyConcurrentExactCap() throws Exception {
        int users = 50;
        int cap = 30;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(users);
        AtomicInteger pass = new AtomicInteger();
        for (long u = 100; u < 100 + users; u++) {
            final long userId = u;
            pool.submit(() -> {
                try {
                    start.await();
                    gate.admit(T1, userId, cap, 10000, 60_000);
                    pass.incrementAndGet();
                } catch (Throwable ignored) {
                    // 被拦 = ServiceException
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(cap, pass.get(), "50 并发恰 30 人通过(滑窗原子判限)");
    }

    /** ⑥sys_config 参数路径: 默认 20/200 种子下同用户重复请求放行(不读显式上限) */
    @Test
    void configDefaultsPathAdmitsSameUser() {
        assertDoesNotThrow(() -> gate.admit("999997", 41L));
        assertDoesNotThrow(() -> gate.admit("999997", 41L));
    }

    /** ⑦上限 0=运营急停(dsflash P2-9): 任一维度为 0 直接拦, 无需发版 */
    @Test
    void zeroMaxMeansKillSwitch() {
        assertThrows(ServiceException.class, () -> gate.admit(T1, 51L, 0, 100, WINDOW_MS), "租户维度 0=急停");
        assertThrows(ServiceException.class, () -> gate.admit(T1, 52L, 100, 0, WINDOW_MS), "全站维度 0=急停");
    }
}
