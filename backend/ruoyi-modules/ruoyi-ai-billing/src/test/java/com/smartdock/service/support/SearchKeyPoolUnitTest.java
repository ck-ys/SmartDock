package com.smartdock.service.support;

import com.smartdock.domain.AiSearchService;
import com.smartdock.mapper.AiSearchServiceMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索钥匙池单测（2026-11-26 多把钥匙拍板 Q3=1）。
 *
 * <p><b>为什么用动态代理假 Mapper 而不是 mockito</b>：钥匙池只该碰 {@code selectList}
 * 一个方法。代理里其余方法一律抛「不该被调用」——若实现悄悄多碰了别的方法，测试会以
 * 明确消息炸掉，而不是静默通过（仓里约定的「null 依赖当探针」同思路）。</p>
 *
 * <p>覆盖板上 Q3 的四个能力：① 多把轮流取 ② 冷却中的跳过 ③ 零余额跳过 ④ 全跳过回退第一把。
 * 外加 Q4 的 {@code lastPickedId} 与错误分类 {@code classify}。</p>
 */
@Tag("local")
class SearchKeyPoolUnitTest {

    /** 只放行 selectList 的假 Mapper；其余方法一律炸（探针） */
    private static AiSearchServiceMapper fakeMapper(List<AiSearchService> rows) {
        InvocationHandler h = (proxy, method, args) -> {
            if ("selectList".equals(method.getName())) {
                return new ArrayList<>(rows);
            }
            throw new UnsupportedOperationException(
                "钥匙池不该调用 " + method.getName() + "（只该用 selectList）");
        };
        return (AiSearchServiceMapper) Proxy.newProxyInstance(
            AiSearchServiceMapper.class.getClassLoader(),
            new Class<?>[]{AiSearchServiceMapper.class}, h);
    }

    private static AiSearchService key(long id, String enabled) {
        AiSearchService k = new AiSearchService();
        k.setId(id);
        k.setTenantId("000000");
        k.setProvider("BOCHA");
        k.setEnabled(enabled);
        k.setApiKey("sk-key-" + id);
        return k;
    }

    private static AiSearchService zeroBalanceKey(long id) {
        AiSearchService k = key(id, "1");
        k.setBalanceStatus("OK");
        k.setBalanceAmount(BigDecimal.ZERO);
        k.setBalanceCheckedAt(new Date());
        return k;
    }

    /* ===== ① 多把轮流取 ===== */

    @Test
    void 三把钥匙_连续取三次_每把各被取到一次() {
        SearchKeyPool pool = new SearchKeyPool(fakeMapper(List.of(key(1, "1"), key(2, "1"), key(3, "1"))));
        Long a = pool.pick("000000", "BOCHA").getId();
        Long b = pool.pick("000000", "BOCHA").getId();
        Long c = pool.pick("000000", "BOCHA").getId();
        assertThat(List.of(a, b, c)).containsExactlyInAnyOrder(1L, 2L, 3L);
    }

    @Test
    void 停用的钥匙不参与轮询() {
        SearchKeyPool pool = new SearchKeyPool(fakeMapper(List.of(key(1, "1"), key(2, "0"), key(3, "1"))));
        for (int i = 0; i < 6; i++) {
            assertThat(pool.pick("000000", "BOCHA").getId()).isNotEqualTo(2L);
        }
    }

    /* ===== ② 冷却中的跳过 ===== */

    @Test
    void 限流429后_这把进入冷却_下一轮跳过它() {
        SearchKeyPool pool = new SearchKeyPool(fakeMapper(List.of(key(1, "1"), key(2, "1"))));
        pool.pick("000000", "BOCHA");            // 取到第一把（seq=0 → id=1）
        pool.reportFailure("000000", "BOCHA", new IOException("HTTP 429 rate limit"));
        for (int i = 0; i < 6; i++) {
            assertThat(pool.pick("000000", "BOCHA").getId()).isEqualTo(2L);
        }
    }

    @Test
    void 成功后清冷却_这把提前回池() {
        SearchKeyPool pool = new SearchKeyPool(fakeMapper(List.of(key(1, "1"), key(2, "1"))));
        pool.pick("000000", "BOCHA");                                  // id=1
        pool.reportFailure("000000", "BOCHA", new IOException("HTTP 429"));
        pool.reportSuccess("000000", "BOCHA");                          // 清掉 id=1 的冷却
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) seen.add(pool.pick("000000", "BOCHA").getId());
        assertThat(seen).containsExactlyInAnyOrder(1L, 2L);
    }

    /* ===== ③ 零余额跳过 ===== */

    @Test
    void 余额为0且刚查过的钥匙_选路跳过() {
        SearchKeyPool pool = new SearchKeyPool(fakeMapper(List.of(zeroBalanceKey(1), key(2, "1"))));
        for (int i = 0; i < 6; i++) {
            assertThat(pool.pick("000000", "BOCHA").getId()).isEqualTo(2L);
        }
    }

    @Test
    void 余额为0但快照很旧_不跳过防误伤() {
        AiSearchService stale = zeroBalanceKey(1);
        stale.setBalanceCheckedAt(new Date(System.currentTimeMillis() - 48L * 3600 * 1000)); // 48 小时前
        SearchKeyPool pool = new SearchKeyPool(fakeMapper(List.of(stale, key(2, "1"))));
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 4; i++) seen.add(pool.pick("000000", "BOCHA").getId());
        assertThat(seen).contains(1L); // 老快照不据此跳过
    }

    /* ===== ④ 全跳过时回退第一把 ===== */

    @Test
    void 全部零余额_回退id最小那把_服务不中断() {
        SearchKeyPool pool = new SearchKeyPool(fakeMapper(List.of(zeroBalanceKey(1), zeroBalanceKey(2))));
        for (int i = 0; i < 4; i++) {
            assertThat(pool.pick("000000", "BOCHA").getId()).isEqualTo(1L);
        }
    }

    @Test
    void 没有enabled行_返回null_让调用方回退系统参数() {
        SearchKeyPool pool = new SearchKeyPool(fakeMapper(List.of(key(1, "0"))));
        assertThat(pool.pick("000000", "BOCHA")).isNull();
    }

    /* ===== Q4：lastPickedId 给计数用 ===== */

    @Test
    void lastPickedId_记录本次实际用的那把() {
        SearchKeyPool pool = new SearchKeyPool(fakeMapper(List.of(key(7, "1"), key(8, "1"))));
        AiSearchService picked = pool.pick("000000", "BOCHA");
        assertThat(pool.lastPickedId("000000", "BOCHA")).isEqualTo(picked.getId());
    }

    @Test
    void 没pick过_lastPickedId为null_计数应跳过() {
        SearchKeyPool pool = new SearchKeyPool(fakeMapper(List.of(key(1, "1"))));
        assertThat(pool.lastPickedId("000000", "BOCHA")).isNull();
    }

    /* ===== 错误分类（纯函数） ===== */

    @Test
    void 错误分类_限流429冷却10分钟() {
        assertThat(SearchKeyPool.classify(new IOException("upstream HTTP 429")))
            .isEqualTo(SearchKeyPool.COOLDOWN_RATE_LIMIT_MS);
    }

    @Test
    void 错误分类_钥匙无效401冷却30分钟() {
        assertThat(SearchKeyPool.classify(new IOException("401 Unauthorized")))
            .isEqualTo(SearchKeyPool.COOLDOWN_KEY_REJECTED_MS);
    }

    @Test
    void 错误分类_网络抖动不冷却() {
        assertThat(SearchKeyPool.classify(new IOException("Connection reset"))).isZero();
    }

    @Test
    void 错误分类_空异常不冷却() {
        assertThat(SearchKeyPool.classify(new RuntimeException())).isZero();
    }
}
