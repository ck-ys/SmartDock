package org.dromara.test.assistant;

import io.modelcontextprotocol.spec.McpSchema;
import org.dromara.assistant.core.AssistantElicitationBridge;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G3-A1 elicitation 桥纯函数与挂起/作答链单测（local 组，无 Spring 依赖；
 * callLogMapper 传 null=审计自吞不连坐——与 McpPilotService 同容错口径）。
 */
@Tag("local")
class G3A1ElicitationBridgeTest {

    private static AssistantElicitationBridge bridge() {
        return new AssistantElicitationBridge(null);
    }

    /** 三态映射：accept/decline/cancel（大小写不敏感）+ content 直传 + 非法返回 null */
    @Test
    void toResultMapsThreeActions() {
        McpSchema.ElicitResult accept = AssistantElicitationBridge.toResult("accept", Map.of("city", "北京"));
        assertEquals(McpSchema.ElicitResult.Action.ACCEPT, accept.action());
        assertEquals("北京", accept.content().get("city"));

        assertEquals(McpSchema.ElicitResult.Action.DECLINE, AssistantElicitationBridge.toResult("DECLINE", null).action());
        assertEquals(McpSchema.ElicitResult.Action.CANCEL, AssistantElicitationBridge.toResult("cancel", null).action());
        // content null 归一空 map（record 不收 null 防 NPE）
        assertEquals(Map.of(), AssistantElicitationBridge.toResult("cancel", null).content());
        assertNull(AssistantElicitationBridge.toResult("yes", null));
        assertNull(AssistantElicitationBridge.toResult(null, null));
    }

    /** 卡载荷映射：id/serverName/message/requestedSchema + remainingMs（三审回修：相对
     *  时长而非绝对时间戳——免疫两端时钟偏差；负值钳 0），null 全兜底 */
    @Test
    void cardPayloadFillsAndGuardsNulls() {
        Map<String, Object> card = AssistantElicitationBridge.cardPayload(
            "e1", "weather", "缺城市，请补充", Map.of("type", "object"), 300_000L);
        assertEquals("e1", card.get("id"));
        assertEquals("weather", card.get("serverName"));
        assertEquals("缺城市，请补充", card.get("message"));
        assertEquals(Map.of("type", "object"), card.get("requestedSchema"));
        assertEquals(300_000L, card.get("remainingMs"));
        // 负/零剩余时长（清扫临界）钳 0——前端拿 0 立即收卡
        assertEquals(0L, AssistantElicitationBridge.cardPayload("e0", null, null, null, -5L).get("remainingMs"));

        Map<String, Object> guarded = AssistantElicitationBridge.cardPayload("e2", null, null, null, 1L);
        assertEquals("", guarded.get("serverName"));
        assertEquals("", guarded.get("message"));
        assertEquals(Map.of(), guarded.get("requestedSchema"));
    }

    /**
     * 挂起→作答闭环：handle 阻塞在子线程，主线程经 take 拿到卡 id，
     * 归属不符拒绝 → 正确归属 answer(accept) → handle 返回 ACCEPT 且 content 透传。
     */
    @Test
    void handleSuspendThenAnswerRoundTrip() throws Exception {
        AssistantElicitationBridge b = bridge();
        AtomicReference<McpSchema.ElicitResult> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(b.handle("weather", "000000", 1L, "th-g3",
            new McpSchema.ElicitRequest("缺城市", Map.of("type", "object")))));
        t.start();
        // 等卡信号可取（handle 先落信号再阻塞）
        List<Map<String, Object>> cards = null;
        for (int i = 0; i < 50 && cards == null; i++) {
            cards = b.take("th-g3");
            if (cards == null) {
                Thread.sleep(20);
            }
        }
        assertNotNull(cards, "卡信号应在 handle 挂起后可取");
        String id = (String) cards.get(0).get("id");
        assertEquals("weather", cards.get(0).get("serverName"));

        // 归属不符：租户不同拒绝，且挂起条目恢复（随后正确归属仍可答）
        assertEquals("该反问不属于当前会话，无法作答",
            b.answer("999999", 1L, "th-g3", id, "accept", null));
        // 正确归属作答
        assertNull(b.answer("000000", 1L, "th-g3", id, "accept", Map.of("city", "北京")));
        t.join(5000);
        assertEquals(McpSchema.ElicitResult.Action.ACCEPT, out.get().action());
        assertEquals("北京", out.get().content().get("city"));

        // 已答再答=幂等人话错误
        assertEquals("该反问已失效（可能已作答或已超时关闭）",
            b.answer("000000", 1L, "th-g3", id, "cancel", null));
    }

    /** 非会话态（threadId 空）立拒 CANCEL 不挂起——防 handler 无限等待 */
    @Test
    void handleWithoutThreadRejectsImmediately() {
        AssistantElicitationBridge b = bridge();
        McpSchema.ElicitResult r = b.handle("weather", "000000", 1L, null,
            new McpSchema.ElicitRequest("?", Map.of()));
        assertEquals(McpSchema.ElicitResult.Action.CANCEL, r.action());
        assertTrue(b.take("th-none") == null);
    }

    /** 三审回修（双席共识 P1）：超时清扫 complete(CANCEL) + pending 同步移除——
     *  晚到的 answer 拿「已失效」而非假成功。@Value 无 Spring 默认 0→钳 60s 等
     *  不起，反射把挂起条目的 expiresAtMs 直接拨过期再触发 sweep（answer 入口顺手扫） */
    @Test
    void timeoutSweepCancelsClearsPendingAndLateAnswerFails() throws Exception {
        AssistantElicitationBridge b = bridge();
        AtomicReference<McpSchema.ElicitResult> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(b.handle("weather", "000000", 1L, "th-to",
            new McpSchema.ElicitRequest("?", Map.of()))));
        t.start();
        List<Map<String, Object>> cards = null;
        for (int i = 0; i < 50 && cards == null; i++) {
            cards = b.take("th-to");
            if (cards == null) {
                Thread.sleep(20);
            }
        }
        assertNotNull(cards);
        String id = (String) cards.get(0).get("id");

        java.lang.reflect.Field pendingField = AssistantElicitationBridge.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) pendingField.get(b);
        assertEquals(1, pending.size(), "挂起中应有 1 条");
        for (Object p : pending.values()) {
            java.lang.reflect.Field ef = p.getClass().getDeclaredField("expiresAtMs");
            ef.setAccessible(true);
            ef.setLong(p, System.currentTimeMillis() - 1);
        }
        // 任意 answer 入口触发懒清扫（此 id 不存在，仅借道 sweep）
        assertNotNull(b.answer("000000", 1L, "th-to", "no-such-id", "accept", null));
        assertTrue(pending.isEmpty(), "过期条目应被清扫并移除（防打满 MAX_PENDING 永久关闭）");
        // 晚到 answer：future 已被 sweep complete(CANCEL) → 如实失效，不假成功
        assertEquals("该反问已失效（可能已作答或已超时关闭）",
            b.answer("000000", 1L, "th-to", id, "accept", Map.of("city", "北京")));
        t.join(5000);
        assertEquals(McpSchema.ElicitResult.Action.CANCEL, out.get().action());
    }

    /** 三审回修（dspro P2-1）：MAX_PENDING=8 满 → 第 9 个反问立返 CANCEL 不挂起；
     *  作答清场后 handle 正常退出（finally 移除不泄漏） */
    @Test
    void maxPendingRejectsNinthAskAndAnswersDrain() throws Exception {
        AssistantElicitationBridge b = bridge();
        List<Thread> ts = new java.util.ArrayList<>();
        java.util.Set<String> ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 8; i++) {
            final int n = i;
            Thread t = new Thread(() -> b.handle("weather", "000000", 1L, "th-m" + n,
                new McpSchema.ElicitRequest("?", Map.of())));
            t.start();
            ts.add(t);
        }
        for (int i = 0; i < 8; i++) {
            List<Map<String, Object>> cards = null;
            for (int k = 0; k < 50 && cards == null; k++) {
                cards = b.take("th-m" + i);
                if (cards == null) {
                    Thread.sleep(20);
                }
            }
            assertNotNull(cards, "第 " + i + " 张卡应挂起");
            ids.add((String) cards.get(0).get("id"));
        }
        assertEquals(8, ids.size());
        AtomicReference<McpSchema.ElicitResult> ninth = new AtomicReference<>();
        Thread t9 = new Thread(() -> ninth.set(b.handle("weather", "000000", 1L, "th-m9",
            new McpSchema.ElicitRequest("?", Map.of()))));
        t9.start();
        t9.join(2000);
        assertTrue(!t9.isAlive(), "pending 满应立返不挂起");
        assertEquals(McpSchema.ElicitResult.Action.CANCEL, ninth.get().action());
        // 全部作答 → handle 线程收尾退出（threadId 传 null 跳过 thread 段比对）
        for (String id : ids) {
            assertNull(b.answer("000000", 1L, null, id, "cancel", null));
        }
        for (Thread t : ts) {
            t.join(5000);
            assertTrue(!t.isAlive(), "作答后 handle 应返回");
        }
    }
}
