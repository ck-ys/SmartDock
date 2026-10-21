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

    /** 卡载荷映射：id/serverName/message/requestedSchema + expiresAt，null 全兜底 */
    @Test
    void cardPayloadFillsAndGuardsNulls() {
        Map<String, Object> card = AssistantElicitationBridge.cardPayload(
            "e1", "weather", "缺城市，请补充", Map.of("type", "object"), 1234567890L);
        assertEquals("e1", card.get("id"));
        assertEquals("weather", card.get("serverName"));
        assertEquals("缺城市，请补充", card.get("message"));
        assertEquals(Map.of("type", "object"), card.get("requestedSchema"));
        assertEquals(1234567890L, card.get("expiresAt"));

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
}
