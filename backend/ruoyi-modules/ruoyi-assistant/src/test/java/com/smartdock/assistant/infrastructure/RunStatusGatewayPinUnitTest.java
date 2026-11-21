package com.smartdock.assistant.infrastructure;

import com.smartdock.assistant.domain.gateway.RunStatusGateway;
import com.smartdock.assistant.domain.model.AssistantConversation;
import com.smartdock.assistant.mapper.AssistantRunMapper;
import com.smartdock.assistant.query.ConversationQueryService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批 7 · 7B（2026-11-20）：{@link RunStatusGateway} 实现 + 会话列表「生成中」回填的钉子。
 *
 * <p>为什么补：Q3 ★ 把「读 run 表标在途」从 legacy 服务抽成跨线网关——这是 7B 新开
 * 的唯一一条跨线读路径，且 {@code ConversationQueryService.markRunning} 是它唯一调用方。
 * 本类钉三件事：① 集合原样透出（不丢不并）；② 空/无在途一律回空集（不返回 null，
 * 调用方无 NPE 面）；③ 回填口径与旧实现逐行一致（有在途=命中 true/其余 false；
 * 无在途=一行不改保持 null），且 rows 空/null 时根本不碰网关（探针：调用即抛）。</p>
 */
@Tag("local")
class RunStatusGatewayPinUnitTest {

    private static final String TENANT = "000000";
    private static final Long USER_ID = 9100000001L;

    // ── 钉 ① 集合原样透出 ────────────────────────────────────────────

    @Test
    void 在途thread集合原样透出() {
        AssistantRunMapper mapper = mock(AssistantRunMapper.class);
        when(mapper.selectRunningThreadIds(TENANT, USER_ID)).thenReturn(List.of("t-a", "t-b"));

        Set<String> out = new RunStatusGatewayImpl(mapper).runningThreadIds(TENANT, USER_ID);
        assertEquals(Set.of("t-a", "t-b"), out);
    }

    // ── 钉 ② 空/无在途 → 空集，不返回 null ──────────────────────────

    @Test
    void 无在途或空列表一律回空集() {
        AssistantRunMapper empty = mock(AssistantRunMapper.class);
        when(empty.selectRunningThreadIds(TENANT, USER_ID)).thenReturn(List.of());
        Set<String> out = new RunStatusGatewayImpl(empty).runningThreadIds(TENANT, USER_ID);
        assertTrue(out.isEmpty());
        assertDoesNotThrow(() -> out.add("x"), "空列表分支同样必须可变");

        AssistantRunMapper nul = mock(AssistantRunMapper.class);
        when(nul.selectRunningThreadIds(TENANT, USER_ID)).thenReturn(null);
        Set<String> out2 = new RunStatusGatewayImpl(nul).runningThreadIds(TENANT, USER_ID);
        assertTrue(out2.isEmpty());
        // javadoc 承诺两个分支都返回可变集合（Set.of() 会抛 UnsupportedOperationException）
        assertDoesNotThrow(() -> out2.add("x"), "空集必须可变");
    }

    // ── 钉 ③ 回填只碰命中行；空 rows 不碰网关 ───────────────────────

    @Test
    void 回填只把命中行标为生成中() {
        AssistantConversation hit = new AssistantConversation();
        hit.setThreadId("t-1");
        AssistantConversation miss = new AssistantConversation();
        miss.setThreadId("t-2");

        RunStatusGateway gateway = (tenantId, userId) -> Set.of("t-1");
        // mapper 传 null：markRunning 只读网关，不碰库——碰了就 NPE 自己证伪
        new ConversationQueryService(null, null, null, gateway)
            .markRunning(List.of(hit, miss), TENANT, USER_ID);

        assertEquals(Boolean.TRUE, hit.getRunning(), "在途 thread 标 true");
        assertEquals(Boolean.FALSE, miss.getRunning(), "同批非在途行标 false（原实现口径，不是 null）");
    }

    @Test
    void 无在途轮时一行都不改() {
        AssistantConversation a = new AssistantConversation();
        a.setThreadId("t-1");

        RunStatusGateway none = (tenantId, userId) -> Set.of();
        new ConversationQueryService(null, null, null, none).markRunning(List.of(a), TENANT, USER_ID);

        assertNull(a.getRunning(), "无 RUNNING 行时保持 null（前端按 false 渲染）");
    }

    @Test
    void rows为空或null时不触碰网关() {
        RunStatusGateway boom = (tenantId, userId) -> {
            throw new AssertionError("rows 为空时不该查 run 表");
        };
        ConversationQueryService svc = new ConversationQueryService(null, null, null, boom);
        svc.markRunning(null, TENANT, USER_ID);
        svc.markRunning(List.of(), TENANT, USER_ID);
    }
}
