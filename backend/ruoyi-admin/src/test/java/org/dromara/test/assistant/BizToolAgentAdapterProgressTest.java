package org.dromara.test.assistant;

import com.smartdock.assistant.spi.BizToolCall;
import com.smartdock.assistant.spi.BizToolProvider;
import com.smartdock.assistant.spi.BizToolResult;
import com.smartdock.assistant.spi.BizToolSpec;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolEmitter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.dromara.assistant.core.AssistantActionLedgerHook;
import org.dromara.assistant.core.BizToolAgentAdapter;
import org.dromara.assistant.tools.ToolIdentity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ckys3 解锁板 题1 案1（2026-11-12）：业务工具慢路真实计时进度的红绿单测。
 *
 * <p>口径（零模拟数据铁律）：业务工具多为单次服务调用，没有可报的真实中间阶段——
 * 只允许报真实经过时间：超 2s 未返回每 2s 一条「执行中·已用时 N 秒」；2s 内完成
 * 零帧（快工具不打扰）。本用例经 callAsync(ToolCallParam) 公共契约驱动（param 携带
 * 框架 executor 注入的 emitter），不直入 doExecute 私有重载——保证与生产路径同构。</p>
 */
@Tag("local")
class BizToolAgentAdapterProgressTest {

    /** 收集进度文本的假 emitter（线程安全：节拍器在独立线程 emit） */
    static class CollectingEmitter implements ToolEmitter {
        final List<String> texts = new CopyOnWriteArrayList<>();

        @Override
        public void emit(ToolResultBlock chunk) {
            if (chunk != null && chunk.getOutput() != null) {
                chunk.getOutput().forEach(b -> {
                    if (b instanceof TextBlock t && t.getText() != null) {
                        texts.add(t.getText());
                    }
                });
            }
        }
    }

    /** 慢 provider：睡 4.5s（跨过 2s/4s 两个节拍）；快 provider：立即返回 */
    static class SlowFastProvider implements BizToolProvider {
        final long sleepMs;
        volatile int executions;

        SlowFastProvider(long sleepMs) {
            this.sleepMs = sleepMs;
        }

        public String namespace() {
            return "toy";
        }

        public List<BizToolSpec> tools() {
            BizToolSpec spec = BizToolSpec.readOnly("toy_slow", "慢工具", "测试用");
            spec.setMinRoleLevel(0);
            return List.of(spec);
        }

        public BizToolResult execute(BizToolCall call) {
            executions++;
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return BizToolResult.ok(Map.of("done", true));
        }
    }

    @BeforeAll
    static void swapSerializer() {
        // 与 BizToolSpiTest 同款纪律：JsonUtils 静态依赖 Spring 上下文，纯单测换原生 Jackson
        com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
        BizToolAgentAdapter.serializer = o -> {
            try { return m.writeValueAsString(o); } catch (Exception e) { throw new RuntimeException(e); }
        };
    }

    @AfterAll
    static void restoreSerializer() {
        // serializer 是全局静态钩子，用完还原防污染同 JVM 其它用例
        BizToolAgentAdapter.serializer = org.dromara.common.json.utils.JsonUtils::toJsonString;
    }

    private static BizToolAgentAdapter adapterOf(BizToolProvider provider) {
        ToolIdentity identity = new ToolIdentity(1L, "000000", 0, null);
        return new BizToolAgentAdapter(provider, provider.tools().get(0),
            identity, "thread-1", null);
    }

    private static ToolCallParam paramWith(CollectingEmitter emitter) {
        return ToolCallParam.builder().input(Map.of()).emitter(emitter).build();
    }

    @Test
    void slowToolEmitsRealElapsedProgressAt2sBeats() {
        SlowFastProvider slow = new SlowFastProvider(4_500L);
        BizToolAgentAdapter adapter = adapterOf(slow);
        CollectingEmitter emitter = new CollectingEmitter();

        ToolResultBlock result = adapter.callAsync(paramWith(emitter)).block();

        assertTrue(result != null && !result.getOutput().isEmpty(), "慢工具最终正常返回");
        assertTrue(emitter.texts.size() >= 2,
            "4.5s 执行应至少跨过 2s/4s 两个节拍，实际帧=" + emitter.texts);
        // 三审席1 P2：首帧不再钉死「2 秒」——机器慢时首报可能落到 3s（CI 单核抢占），
        // 断言语义放宽为「首帧 ≥ 2 秒的真计时」
        int firstSec = Integer.parseInt(emitter.texts.get(0).replaceAll("[^0-9]", ""));
        assertTrue(firstSec >= 2, "首帧应为真实经过时间 ≥2 秒，实际=" + emitter.texts.get(0));
        assertTrue(emitter.texts.stream().allMatch(t -> t.startsWith("执行中·已用时")),
            "只报真实经过时间，不编造步骤，实际=" + emitter.texts);
        assertEquals(1, slow.executions, "进度不改执行次数");
    }

    @Test
    void fastToolEmitsNothing() {
        SlowFastProvider fast = new SlowFastProvider(50L);
        BizToolAgentAdapter adapter = adapterOf(fast);
        CollectingEmitter emitter = new CollectingEmitter();

        adapter.callAsync(paramWith(emitter)).block();

        assertEquals(0, emitter.texts.size(), "2s 内完成零帧（快工具不打扰）");
    }
}
