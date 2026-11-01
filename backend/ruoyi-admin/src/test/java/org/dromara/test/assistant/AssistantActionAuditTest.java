package org.dromara.test.assistant;

import org.dromara.assistant.core.AssistantActionLedgerHook;
import org.dromara.assistant.core.AssistantReleaseMode;
import org.dromara.assistant.core.AssistantRunContext;
import org.dromara.assistant.core.ProviderToolRegistry;
import org.dromara.assistant.service.AssistantActionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三批③（2026-11-01）：写操作审计挂钩纯函数/行为单测（不起 Spring——Port 造假）。
 *
 * <p>护栏重点：①同 run 多笔写操作幂等键不撞（seq 自增）；②键超 128 截断；
 * ③refIdOf 垃圾值不猜；④hook 任何账本异常不外传（不炸工具执行）；
 * ⑤markDone 尾段顺序钉死（总长 ≤500 且「（记录 #id）」尾段完整可解析）。</p>
 */
@Tag("local")
class AssistantActionAuditTest {

    @AfterEach
    void clearRunContext() {
        AssistantRunContext.clear();
    }

    /** 幂等键拼接：同 run 同工具两笔（seq 不同）不撞；超 128 截断对齐列宽 */
    @Test
    void buildKeySeqSeparatesAndTruncates() {
        String k1 = AssistantActionLedgerHook.buildKey("000000", "run-1", "save_memory", 1);
        String k2 = AssistantActionLedgerHook.buildKey("000000", "run-1", "save_memory", 2);
        assertNotEquals(k1, k2, "同 run 两笔 save_memory 键不撞（seq 自增）");
        assertTrue(k1.endsWith(":save_memory:1") && k2.endsWith(":save_memory:2"), "键形=assistant:{tenant}:{run}:{tool}:{seq}");

        String huge = AssistantActionLedgerHook.buildKey("000000", "r".repeat(120), "save_memory", 1);
        assertEquals(128, huge.length(), "键超 128 截断（列宽口径）");
        String tiny = AssistantActionLedgerHook.buildKey("000000", "run-1", "save_memory", 1);
        assertTrue(tiny.length() <= 128);
    }

    /** hook 落行行为（假 Port）：两次 open 键不撞、release_mode 按快照判定、args 原样带过 */
    @Test
    void hookOpenProducesDistinctKeysAndReleaseMode() {
        AssistantRunContext.refresh("run-audit-1", "th-audit");
        List<String> keys = new ArrayList<>();
        List<String> modes = new ArrayList<>();
        AssistantActionLedgerHook.Port port = new AssistantActionLedgerHook.Port() {
            @Override
            public Long open(String tenantId, Long userId, String runId, String threadId, String toolName,
                             String argsJson, String idempotencyKey, String releaseMode) {
                assertEquals("000000", tenantId);
                assertEquals(Long.valueOf(7L), userId);
                assertEquals("run-audit-1", runId, "流线程可读到当前 runId（不降级 UUID）");
                keys.add(idempotencyKey);
                modes.add(releaseMode);
                return 101L;
            }

            @Override
            public void finish(Long rowId, boolean ok, String summary, Long refId) {
            }
        };
        // 放行集只含 dcs_create_task（auto 快照）；save_memory 不在集合但按平台放行记 allow
        ProviderToolRegistry.AuditScope scope =
            new ProviderToolRegistry.AuditScope(Set.of("dcs_create_task"), Set.of("dcs_create_task"));
        AssistantActionLedgerHook hook = new AssistantActionLedgerHook(port, "000000", 7L, "th-audit", scope);

        assertEquals(Long.valueOf(101L), hook.open("save_memory", "{\"content\":\"称呼我为小林\"}"));
        assertEquals(Long.valueOf(101L), hook.open("dcs_create_task", "{\"title\":\"t\"}"));
        assertEquals(2, keys.size());
        assertNotEquals(keys.get(0), keys.get(1));
        assertEquals(AssistantReleaseMode.ALLOW, modes.get(0), "save_memory=平台放行（不在放行集也 allow）");
        assertEquals(AssistantReleaseMode.AUTO, modes.get(1), "放行集内（建时 auto 快照）=auto");
    }

    /** 账本写入失败绝不外传：open/finish 抛异常→hook 吞掉（warn 留痕），open 返回 null */
    @Test
    void hookSwallowsLedgerFailures() {
        AssistantActionLedgerHook.Port bomb = new AssistantActionLedgerHook.Port() {
            @Override
            public Long open(String tenantId, Long userId, String runId, String threadId, String toolName,
                             String argsJson, String idempotencyKey, String releaseMode) {
                throw new IllegalStateException("db down");
            }

            @Override
            public void finish(Long rowId, boolean ok, String summary, Long refId) {
                throw new IllegalStateException("db down");
            }
        };
        AssistantActionLedgerHook hook = new AssistantActionLedgerHook(bomb, "000000", 7L, "th-audit",
            new ProviderToolRegistry.AuditScope(Set.of(), Set.of()));
        assertNull(hook.open("dcs_create_task", "{}"), "open 抛异常→null（不炸工具执行）");
        hook.finish(101L, true, "执行完成", null); // finish 抛异常也不外传
        hook.finish(null, true, "执行完成", null); // rowId=null no-op
    }

    /** refIdOf：正常数字（Number/数字串）→long；垃圾值/缺键/非 map→null（不猜） */
    @Test
    void refIdOfTakesTaskIdOnly() {
        assertEquals(123L, AssistantActionLedgerHook.refIdOf(Map.of("taskId", 123L)));
        assertEquals(456L, AssistantActionLedgerHook.refIdOf(Map.of("taskId", "456")));
        assertNull(AssistantActionLedgerHook.refIdOf(Map.of("taskId", "abc")), "垃圾字符串→null");
        assertNull(AssistantActionLedgerHook.refIdOf(Map.of("taskId", "12x3")), "半数字半垃圾→null");
        assertNull(AssistantActionLedgerHook.refIdOf(Map.of("other", 1)), "缺 taskId 键→null");
        assertNull(AssistantActionLedgerHook.refIdOf(null));
        assertNull(AssistantActionLedgerHook.refIdOf("not-a-map"));
    }

    /** markDone 尾段顺序：长 summary+refId→总长 ≤500 且「（记录 #id）」尾段完整落在结尾 */
    @Test
    void withRefTailPinsTailWithin500() {
        String tail = "（记录 #910000000000201）";
        String out = AssistantActionService.withRefTail("执".repeat(600), 910000000000201L);
        assertTrue(out.length() <= 500, "总长恒 ≤500");
        assertTrue(out.endsWith(tail), "尾段完整落在结尾（先截正文再拼尾段）");
        assertEquals(tail, out.substring(out.length() - tail.length()));

        // 短正文不截断，尾段原样拼接
        String shortOut = AssistantActionService.withRefTail("已记住：称呼我为小林", 42L);
        assertEquals("已记住：称呼我为小林（记录 #42）", shortOut);

        // refId=null=纯摘要（与旧 markDone 行为一致，500 截断）
        assertNull(AssistantActionService.withRefTail(null, null));
        assertEquals(500, AssistantActionService.withRefTail("a".repeat(600), null).length());
    }

    /** 放行方式展示映射（审计页/CSV 共用口径；ruoyi-admin 侧消费断言） */
    @Test
    void releaseDisplayMapping() {
        assertEquals("确认后执行", AssistantReleaseMode.display("confirm"));
        assertEquals("同类已确认", AssistantReleaseMode.display("once"));
        assertEquals("全自动", AssistantReleaseMode.display("auto"));
        assertEquals("平台放行", AssistantReleaseMode.display("allow"));
        assertEquals("—", AssistantReleaseMode.display(null), "REJECTED 行/历史行空值→长破折");
    }
}
