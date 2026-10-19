package com.smartdock.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.domain.DcsAiProviderAccess;
import com.smartdock.mapper.DcsAiProviderAccessMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 钥匙池单测（AI 缺口批 Q8，2026-10-14 拍板全A）：
 * ① 轮询顺序（两把钥匙交替）与 disabled 排除；
 * ② 冷却跳过（429 冷却后换另一把）+ 全冷却回退 id 最小行（服务不中断）；
 * ③ 失败分类纯函数（429=10min / 401=30min / 超时类=0 不冷却）；
 * ④ 零余额跳过（OK+0+24h 内才跳；快照过期/状态 FAILED 不跳防误伤）；
 * ⑤ 成功上报清冷却；evict 清指定钥匙。
 */
@ExtendWith(MockitoExtension.class)
class ProviderKeyPoolUnitTest {

    @Mock
    private DcsAiProviderAccessMapper accessMapper;

    private ProviderKeyPool pool() {
        return new ProviderKeyPool(accessMapper);
    }

    private static DcsAiProviderAccess key(long id, String enabled) {
        DcsAiProviderAccess k = new DcsAiProviderAccess();
        k.setId(id);
        k.setTenantId("T1");
        k.setProvider("SILICONFLOW");
        k.setBaseUrl("https://api.siliconflow.cn/v1");
        k.setApiKey("sk-" + id);
        k.setEnabled(enabled);
        return k;
    }

    private void stub(DcsAiProviderAccess... keys) {
        when(accessMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(keys));
    }

    @Test
    @Tag("dev")
    void 两把钥匙轮询交替_disabled不进池() {
        ProviderKeyPool p = pool();
        stub(key(1, "1"), key(2, "1"), key(3, "0"));
        long first = p.pick("T1", "SILICONFLOW").getId();
        long second = p.pick("T1", "SILICONFLOW").getId();
        assertThat(first).isIn(1L, 2L);
        assertThat(second).isNotEqualTo(first);
        // 连续多轮只见 1/2 两把
        for (int i = 0; i < 6; i++) {
            assertThat(p.pick("T1", "SILICONFLOW").getId()).isIn(1L, 2L);
        }
    }

    @Test
    @Tag("dev")
    void enabled为null视同启用_存量行语义() {
        ProviderKeyPool p = pool();
        stub(key(7, null));
        assertThat(p.pick("T1", "SILICONFLOW").getId()).isEqualTo(7L);
    }

    @Test
    @Tag("dev")
    void 冷却跳过_429冷却后走另一把() {
        ProviderKeyPool p = pool();
        stub(key(1, "1"), key(2, "1"));
        Long picked = p.pick("T1", "SILICONFLOW").getId();
        p.reportFailure("T1", "SILICONFLOW",
            new RuntimeException("HTTP 429 Too Many Requests"));
        // 冷却中的被跳过，拿到另一把
        assertThat(p.pick("T1", "SILICONFLOW").getId()).isNotEqualTo(picked);
        assertThat(p.cooldownUntil("T1", "SILICONFLOW", picked))
            .isGreaterThan(System.currentTimeMillis());
    }

    @Test
    @Tag("dev")
    void 全冷却回退id最小行_服务不中断() {
        ProviderKeyPool p = pool();
        stub(key(1, "1"), key(2, "1"));
        p.pick("T1", "SILICONFLOW");
        p.pick("T1", "SILICONFLOW");
        p.reportFailure("T1", "SILICONFLOW", new RuntimeException("401 Unauthorized"));
        p.reportFailure("T1", "SILICONFLOW", new RuntimeException("402 Payment Required"));
        // 两把都进了冷却 → 回退 id 最小（1 号），不返回 null 不抛错
        assertThat(p.pick("T1", "SILICONFLOW").getId()).isEqualTo(1L);
    }

    @Test
    @Tag("dev")
    void 成功上报清冷却_冷却时长不缩短() {
        ProviderKeyPool p = pool();
        assertThat(ProviderKeyPool.classify(new RuntimeException("429 rate limit"))).isEqualTo(600_000L);
        assertThat(ProviderKeyPool.classify(new RuntimeException("401 Unauthorized"))).isEqualTo(1_800_000L);
        assertThat(ProviderKeyPool.classify(new RuntimeException("402 insufficient balance"))).isEqualTo(1_800_000L);
        assertThat(ProviderKeyPool.classify(new RuntimeException("Connection reset"))).isEqualTo(0L);
        assertThat(ProviderKeyPool.classify(new RuntimeException("read timeout 30000ms"))).isEqualTo(0L);

        stub(key(1, "1"), key(2, "1"));
        p.pick("T1", "SILICONFLOW");
        p.reportFailure("T1", "SILICONFLOW", new RuntimeException("429"));
        assertThat(p.cooldownUntil("T1", "SILICONFLOW", 1L)).isPositive();
        p.reportSuccess("T1", "SILICONFLOW");
        assertThat(p.cooldownUntil("T1", "SILICONFLOW", 1L)).isZero();
    }

    @Test
    @Tag("dev")
    void 零余额跳过_仅新鲜OK快照_过期或FAILED不跳() {
        ProviderKeyPool p = pool();
        DcsAiProviderAccess zero = key(1, "1");
        zero.setBalanceStatus("OK");
        zero.setBalanceAmount(BigDecimal.ZERO);
        zero.setBalanceCheckedAt(new Date()); // 刚确认没钱 → 跳
        DcsAiProviderAccess healthy = key(2, "1");
        stub(zero, healthy);
        assertThat(p.pick("T1", "SILICONFLOW").getId()).isEqualTo(2L);

        // 快照超过 24h → 不再据此跳过（旧数据不误伤）
        p.resetForTest();
        zero.setBalanceCheckedAt(new Date(System.currentTimeMillis() - 3 * 24 * 3600 * 1000L));
        assertThat(p.pick("T1", "SILICONFLOW").getId()).isEqualTo(1L);

        // FAILED 状态（探测失败保留旧值）→ 不跳
        p.resetForTest();
        zero.setBalanceCheckedAt(new Date());
        zero.setBalanceStatus("FAILED");
        assertThat(p.pick("T1", "SILICONFLOW").getId()).isEqualTo(1L);
    }

    @Test
    @Tag("dev")
    void 池空返回null_调用方回退旧行为() {
        ProviderKeyPool p = pool();
        when(accessMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertThat(p.pick("T1", "SILICONFLOW")).isNull();
    }

    @Test
    @Tag("dev")
    void evict清指定钥匙冷却() {
        ProviderKeyPool p = pool();
        stub(key(1, "1"), key(2, "1"));
        p.pick("T1", "SILICONFLOW");
        p.reportFailure("T1", "SILICONFLOW", new RuntimeException("429"));
        assertThat(p.cooldownUntil("T1", "SILICONFLOW", 1L)).isPositive();
        p.evict("T1", "SILICONFLOW", 1L);
        assertThat(p.cooldownUntil("T1", "SILICONFLOW", 1L)).isZero();
    }
}
