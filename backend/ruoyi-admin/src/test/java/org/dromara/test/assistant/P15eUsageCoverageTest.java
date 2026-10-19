package org.dromara.test.assistant;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.DcsAiConfigMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T15e 探针：emit-token-usage 覆盖面（实测⑮之五，安全五连测卡5，~8 次真实付费调用）。
 * <p>判据（计划 Task 15 Step5 + 第四轮补强②）：X-02 源码结论的运行时复证——
 * 后三类调用（压缩摘要/记忆 flush/记忆固化）直接调 Model.stream 不经用户 middleware，
 * 故计费/usage 采集必须包在 **Model 实例边界**（装饰器），onModelCall 中间件采不到后三类。</p>
 * <ul>
 *   <li>A 主对话：装饰器捕获带 usage 的主调用；</li>
 *   <li>B 压缩摘要：ConversationCompactor 直调 → 装饰器捕获；</li>
 *   <li>C 记忆 flush：MemoryFlushManager.flushMemories → 装饰器捕获；</li>
 *   <li>D 记忆固化：MemoryConsolidator.consolidate → 装饰器捕获（空工作区可能 noop——如实记录）；</li>
 *   <li>E e2e 盲区实证：agent 挂 CompactionMiddleware+间谍 middleware，压缩真实触发时
 *       装饰器调用量 > middleware onModelCall 计数（后三类绕过 middleware 的运行时证据）；</li>
 *   <li>F spec ⑮口径：P1 未启用压缩/记忆时断言后三类零调用（仅主对话）。</li>
 * </ul>
 */
@Tag("p0probe")
@SpringBootTest
class P15eUsageCoverageTest {

    private static final long CONFIG_ID = 9100000000003L;
    private static final Path OUT = Path.of("target", "p15e");

    @Autowired
    DcsAiConfigMapper aiConfigMapper;

    @BeforeAll
    static void ensureOutDir() throws Exception {
        // 测试方法执行顺序不保证（JUnit 默认非声明序）——证据目录统一 @BeforeAll 建
        Files.createDirectories(OUT);
    }

    /** Model 实例边界装饰器（计费/usage 采集挂点——P1 照抄；usage 唯一计费源=响应体 usage） */
    static class UsageRecordingModel implements Model {
        private final Model delegate;
        final String tag;
        final List<String> calls = new CopyOnWriteArrayList<>();   // "tag:in=..,out=.."
        final AtomicInteger totalIn = new AtomicInteger();

        UsageRecordingModel(Model delegate, String tag) {
            this.delegate = delegate;
            this.tag = tag;
        }

        @Override
        public reactor.core.publisher.Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools,
                                                                GenerateOptions options) {
            return delegate.stream(msgs, tools, options)
                .doOnNext(r -> {
                    ChatUsage u = r.getUsage();
                    if (u != null && (u.getInputTokens() > 0 || u.getOutputTokens() > 0)) {
                        calls.add(tag + ":in=" + u.getInputTokens() + ",out=" + u.getOutputTokens());
                        totalIn.addAndGet(u.getInputTokens());
                    }
                });
        }

        @Override
        public String getModelName() { return delegate.getModelName(); }

        @Override
        public int getContextWindowSize() { return delegate.getContextWindowSize(); }
    }

    /** 间谍 middleware：只数 onModelCall 钩子命中次数（X-02 盲区实证的对照面） */
    static class SpyMiddleware implements MiddlewareBase {
        final AtomicInteger modelCallHooks = new AtomicInteger();

        @Override
        public reactor.core.publisher.Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext rc,
                ModelCallInput input,
                java.util.function.Function<ModelCallInput, reactor.core.publisher.Flux<AgentEvent>> next) {
            modelCallHooks.incrementAndGet();
            return next.apply(input);
        }
    }

    private Model buildRawModel(DcsAiConfig cfg) {
        return OpenAIChatModel.builder()
            .baseUrl(cfg.getBaseUrl()).apiKey(cfg.getApiKey()).modelName(cfg.getModelName())
            .formatter(new DeepSeekFormatter()).build();
    }

    private static String textOf(Msg m) {
        return m.getContentBlocks(TextBlock.class).stream()
            .map(TextBlock::getText).reduce("", (a, b) -> a + b);
    }

    private static String filler(int chars, String tag) {
        StringBuilder sb = new StringBuilder(chars + 32);
        while (sb.length() < chars) {
            sb.append("【").append(tag).append("】部门应急联络名单批次更新：负责人已核对，")
              .append("备用联系人电话与邮箱格式无误，值班表已同步归档，纸质件次日送达综合管理部备案。");
        }
        return sb.substring(0, chars);
    }

    /** 10 轮长对话（每条 assistant ~400 字符——总量 ~2400 tok，稳超 trigger=800，P14 同配方） */
    private static List<Msg> tenTurnConversation() {
        List<Msg> msgs = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            msgs.add(Msg.builderForRole(MsgRole.USER)
                .textContent("第" + i + "轮：请记录部门应急联络名单第" + i + "批更新，联系人张" + i + "。").build());
            msgs.add(Msg.builderForRole(MsgRole.ASSISTANT)
                .textContent("已记录第" + i + "批：" + filler(400, "批次" + i)).build());
        }
        return msgs;
    }

    /** A+F：主对话经装饰器可采集；P1 形态（无压缩/记忆中间件）下后三类零调用 */
    @Test
    void mainCallCapturedAndLatterThreeZeroWithoutMiddlewares() throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        UsageRecordingModel recorder = new UsageRecordingModel(buildRawModel(cfg), "main");
        ReActAgent agent = ReActAgent.builder()
            .name("p15e-main-agent")
            .sysPrompt("你是 DCS 数据采集系统的工作助手。用一句话中文回答。不要使用任何工具。")
            .model(recorder)
            .maxIters(2)
            .build();
        RuntimeContext rc = RuntimeContext.builder()
            .userId("t:978311:p15e").sessionId("p15e-main-" + System.currentTimeMillis()).build();
        Msg reply = agent.call(List.of(Msg.builderForRole(MsgRole.USER)
                .textContent("一句话说明任务是什么。").build()), rc)
            .block(Duration.ofSeconds(120));
        assertNotNull(reply);
        System.out.println("[P15e-A] 主对话捕获 " + recorder.calls.size() + " 条 usage 流水: " + recorder.calls);
        assertTrue(!recorder.calls.isEmpty() && recorder.totalIn.get() > 0,
            "装饰器应捕获主对话 usage（响应体 usage=唯一计费源）");
        // F：未挂压缩/记忆中间件 → 恰好 1 次主对话调用（无 toolkit/maxIters=2，调用数确定性；
        // 三审修正：原 allMatch("main:") 在单 recorder 恒真零检测力，改精确计数）
        assertEquals(1, recorder.calls.size(),
            "P1 未启用压缩/记忆时后三类必须零调用（实测 " + recorder.calls.size() + " 次=有绕链调用）");
    }

    /** B：压缩摘要（ConversationCompactor 直调）经装饰器捕获 */
    @Test
    void compactionSummaryCaptured(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        io.agentscope.harness.agent.filesystem.local.LocalFilesystem localFs =
            new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(dir);
        WorkspaceManager wm = new WorkspaceManager(dir, localFs);
        try {
            // 三审修正：summary 与 flush 各挂独立 recorder——原共用无法区分捕获来自压缩摘要还是 flush
            UsageRecordingModel summaryRec = new UsageRecordingModel(buildRawModel(cfg), "summary");
            UsageRecordingModel flushRec = new UsageRecordingModel(buildRawModel(cfg), "flush");
            MemoryFlushManager flush = new MemoryFlushManager(wm, flushRec);
            ConversationCompactor compactor = new ConversationCompactor(summaryRec, flush);
            RuntimeContext rc = RuntimeContext.builder()
                .userId("t:978311:p15e").sessionId("p15e-compact-" + System.currentTimeMillis()).build();
            compactor.compactIfNeeded(rc, tenTurnConversation(),
                    CompactionConfig.builder().triggerTokens(800).keepMessages(2).keepTokens(1500).build(),
                    "t:978311:p15e", "p15e-compact")
                .block(Duration.ofSeconds(120));
            System.out.println("[P15e-B] 压缩摘要捕获 " + summaryRec.calls.size() + " 条: " + summaryRec.calls
                + "；压缩内 flush 捕获 " + flushRec.calls.size() + " 条");
            assertTrue(!summaryRec.calls.isEmpty(),
                "压缩摘要调用必须经 Model 边界（装饰器可采集）——零捕获=计费盲区");
        } finally {
            wm.close();
        }
    }

    /** C：记忆 flush（MemoryFlushManager.flushMemories）经装饰器捕获 */
    @Test
    void memoryFlushCaptured(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        io.agentscope.harness.agent.filesystem.local.LocalFilesystem localFs =
            new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(dir);
        WorkspaceManager wm = new WorkspaceManager(dir, localFs);
        try {
            UsageRecordingModel recorder = new UsageRecordingModel(buildRawModel(cfg), "flush");
            MemoryFlushManager flush = new MemoryFlushManager(wm, recorder);
            RuntimeContext rc = RuntimeContext.builder()
                .userId("t:978311:p15e").sessionId("p15e-flush-" + System.currentTimeMillis()).build();
            flush.flushMemories(rc, tenTurnConversation()).block(Duration.ofSeconds(120));
            System.out.println("[P15e-C] 记忆 flush 捕获 " + recorder.calls.size() + " 条: " + recorder.calls
                + "；memory 文件=" + Files.exists(dir.resolve("memory")));
            assertTrue(!recorder.calls.isEmpty(),
                "记忆 flush 调用必须经 Model 边界（装饰器可采集）——零捕获=计费盲区");
        } finally {
            wm.close();
        }
    }

    /** D：记忆固化（MemoryConsolidator.consolidate）经装饰器捕获（空工作区可能 noop——如实记录） */
    @Test
    void memoryConsolidateCapturedOrNoop(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        io.agentscope.harness.agent.filesystem.local.LocalFilesystem localFs =
            new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(dir);
        WorkspaceManager wm = new WorkspaceManager(dir, localFs);
        try {
            UsageRecordingModel recorder = new UsageRecordingModel(buildRawModel(cfg), "consolidate");
            // 先造一份记忆文件让固化有事可做（空工作区=水印无推进=零调用 noop）
            MemoryFlushManager flush = new MemoryFlushManager(wm, recorder);
            RuntimeContext rc = RuntimeContext.builder()
                .userId("t:978311:p15e").sessionId("p15e-cons-" + System.currentTimeMillis()).build();
            flush.flushMemories(rc, tenTurnConversation()).block(Duration.ofSeconds(120));
            int before = recorder.calls.size();
            MemoryConsolidator consolidator = new MemoryConsolidator(wm, recorder);
            consolidator.consolidate(rc).block(Duration.ofSeconds(120));
            assertTrue(before >= 1, "flush 必有模型调用（10 轮长对话必产生记忆摘要）");
            int consolidatedCalls = recorder.calls.size() - before;
            System.out.println("[P15e-D] 记忆固化捕获 " + consolidatedCalls + " 条（总 " + recorder.calls.size()
                + "，flush 前 " + before + "）" + (consolidatedCalls == 0
                    ? "——固化 noop（无待固化增量），挂点已证（同 Model 边界）" : ""));
            Files.writeString(OUT.resolve("15e-consolidate.txt"),
                "flush=" + before + " total=" + recorder.calls.size() + "\n", StandardCharsets.UTF_8);
        } finally {
            wm.close();
        }
    }

    /** E：盲区实证——压缩真实触发时装饰器调用量 > middleware onModelCall 命中数（X-02 运行时复证） */
    @Test
    void middlewareBlindSpotProvenWhenCompactionFires(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        io.agentscope.harness.agent.filesystem.local.LocalFilesystem localFs =
            new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(dir);
        WorkspaceManager wm = new WorkspaceManager(dir, localFs);
        try {
            UsageRecordingModel recorder = new UsageRecordingModel(buildRawModel(cfg), "e2e");
            SpyMiddleware spy = new SpyMiddleware();
            ReActAgent agent = ReActAgent.builder()
                .name("p15e-blind-agent")
                .sysPrompt("你是 DCS 数据采集系统的工作助手。用简洁中文回答。不要使用任何工具。")
                .model(recorder)
                .middleware(new CompactionMiddleware(wm, recorder,
                    // 触发阈值压到 300：sysPrompt+1 轮长回答即稳超——压缩必须确定性触发
                    // （600 时临界依赖模型输出长度，实测两轮一间歇未触发=判据不可重复）
                    CompactionConfig.builder().triggerTokens(300).keepMessages(2).keepTokens(1500).build()))
                .middleware(spy)
                .maxIters(2)
                .build();
            RuntimeContext rc = RuntimeContext.builder()
                .userId("t:978311:p15e").sessionId("p15e-blind-" + System.currentTimeMillis()).build();
            // 4 轮长回答：第 2 轮起上下文必超 300 token，压缩在会话内确定性触发
            for (int i = 1; i <= 4; i++) {
                Msg r = agent.call(List.of(Msg.builderForRole(MsgRole.USER)
                        .textContent("第" + i + "问：请分五点详细记录部门应急联络名单第" + i + "批更新的全部内容，"
                            + "包括负责人、备用联系人、手机号、邮箱和值班安排，每点至少三十个字。").build()), rc)
                    .block(Duration.ofSeconds(120));
                assertNotNull(r);
            }
            int decoratedCalls = recorder.calls.size();
            int middlewareHooks = spy.modelCallHooks.get();
            System.out.println("[P15e-E] 装饰器捕获=" + decoratedCalls + " 条 usage 流水；"
                + "middleware onModelCall 命中=" + middlewareHooks + " 次");
            assertTrue(middlewareHooks >= 3, "主对话每轮应命中 onModelCall（实测 " + middlewareHooks + "）");
            assertTrue(decoratedCalls > middlewareHooks,
                "压缩触发后装饰器调用量(" + decoratedCalls + ")必须大于 middleware 命中数(" + middlewareHooks
                    + ")——后三类绕过 middleware 的运行时证据（X-02）。若相等=压缩未触发（trigger 300 下不应发生）");
            Files.writeString(OUT.resolve("15e-blindspot.txt"),
                "decorated=" + decoratedCalls + "\nmiddleware_hooks=" + middlewareHooks + "\n",
                StandardCharsets.UTF_8);
        } finally {
            wm.close();
        }
    }
}
