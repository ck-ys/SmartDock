package org.dromara.test.assistant;

import org.dromara.assistant.limiter.AssistantWebSearchQuota;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 业务批3-④（#17，2026-10-12）联网搜索日限额单测：Lua 原子（限 N 过 N 拒第 N+1、
 * 被拒不计数）、refundSearch 补偿、resetToday 一键清零。镜像 G5QuotaTest 模式。
 */
@Tag("p0probe")
@SpringBootTest
class AssistantWebSearchQuotaTest {

    private static final Long U = 910000000000401L;
    private static final String TENANT = "995311";

    @Autowired
    AssistantWebSearchQuota quota;

    @AfterEach
    void cleanup() {
        var ks = RedisUtils.keys("t:" + TENANT + ":quota:websearch:" + U + ":*");
        if (ks != null && !ks.isEmpty()) {
            RedisUtils.deleteObject(ks);
        }
    }

    @Test
    void searchGateLimitAndRefund() {
        for (int i = 0; i < 3; i++) {
            assertEquals(AssistantWebSearchQuota.Gate.COUNTED, quota.trySearch(TENANT, U, 3), "前 3 次放行且已计数");
        }
        // 第 4 次被拒且不计数（宁少拒不多扣：被拒尝试不消耗额度）
        assertEquals(AssistantWebSearchQuota.Gate.DENIED, quota.trySearch(TENANT, U, 3), "超限第 4 次必须拒绝");
        assertEquals(3, quota.searchesUsed(TENANT, U), "计数锚定 3 不因被拒加 1");

        // 上游失败补偿：回退一次后可再搜
        quota.refundSearch(TENANT, U);
        assertEquals(AssistantWebSearchQuota.Gate.COUNTED, quota.trySearch(TENANT, U, 3), "补偿后放行");
        assertEquals(3, quota.searchesUsed(TENANT, U), "补偿+重搜=计数回到 3");
    }

    @Test
    void zeroLimitMeansOff() {
        assertEquals(AssistantWebSearchQuota.Gate.DENIED, quota.trySearch(TENANT, U, 0), "0=联网关到零（回合额度仍在）");
        assertEquals(0, quota.searchesUsed(TENANT, U), "被拒不计数");
    }

    @Test
    void resetTodayClearsCount() {
        assertEquals(AssistantWebSearchQuota.Gate.COUNTED, quota.trySearch(TENANT, U, 5));
        assertEquals(AssistantWebSearchQuota.Gate.COUNTED, quota.trySearch(TENANT, U, 5));
        quota.resetToday(TENANT, U);
        assertEquals(0, quota.searchesUsed(TENANT, U), "管理端重置后清零");
        assertEquals(AssistantWebSearchQuota.Gate.COUNTED, quota.trySearch(TENANT, U, 5), "重置后恢复可用");
    }
}
