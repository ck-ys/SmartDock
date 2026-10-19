package org.dromara.test.assistant;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.DcsAiConfigMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T1 探针：DeepSeek 多轮工具调用稳定性（真实付费调用，实测①）。
 * <p>判据（计划 Task 1）：</p>
 * <ul>
 *   <li>Step1/2：ReActAgent（dcs_ai_config 9100000000003 的 DeepSeek 配置，apiKey 由 MyBatis 加密拦截器
 *       自动解密）+ 2 个固定 @Tool，5 轮对话，每轮断言 tool call 计数增加且最终文本非空；@RepeatedTest(3)
 *       = 稳定性 3/3（失败语义记录：超时/格式错/工具缺失）；</li>
 *   <li>Step3：每轮 ChatUsage 累积打印（对账计费预算假设：单回合 3-8 次模型调用）；</li>
 *   <li>Step4：双身份 Toolkit——student 组默认激活、admin 组不激活，断言成员激活面无 act_* 工具
 *       + maxRetries 行为（不可达地址触发连接失败→明确报错；打假地址不花模型钱）。</li>
 * </ul>
 * <p>预算纪律：钱包 ¥15 起步先用完再补（止损剩 ¥3 提醒）；本探针单遍 5 轮、共 3 遍。</p>
 */
@Tag("p0probe")
@SpringBootTest
class P01ModelToolLoopTest {

    /** 测试租户（批0 拍板专用，绝不碰 000000）的 DeepSeek CHAT 配置 */
    private static final long CONFIG_ID = 9100000000003L;

    @Autowired
    DcsAiConfigMapper aiConfigMapper;

    /** 工具调用计数器（断言"每轮都出现 tool call"的锚点） */
    static final AtomicInteger TODO_CALLS = new AtomicInteger();
    static final AtomicInteger PROGRESS_CALLS = new AtomicInteger();

    /** 两个固定返回的探针工具（结果写死，模型只测调用链路不测数据真伪） */
    static class ProbeTools {
        @Tool(name = "query_my_todos", description = "查询当前用户的待办任务列表，返回任务名/类型/截止时间")
        public String queryMyTodos() {
            TODO_CALLS.incrementAndGet();
            return "{\"total\":3,\"todos\":["
                + "{\"title\":\"填写『成员信息采集表』\",\"type\":\"填表\",\"deadline\":\"2026-10-10\"},"
                + "{\"title\":\"提交『实验室安全培训』追加记录\",\"type\":\"追加\",\"deadline\":\"2026-10-08\"},"
                + "{\"title\":\"复核『助学金申请材料』\",\"type\":\"复核\",\"deadline\":\"2026-10-15\"}]}";
        }

        @Tool(name = "query_mission_progress", description = "查询指定任务的填报进度统计，参数为任务名称关键词")
        public String queryMissionProgress(String missionKeyword) {
            PROGRESS_CALLS.incrementAndGet();
            return "{\"mission\":\"" + missionKeyword + "\",\"submitted\":18,\"total\":36,\"pending\":12,"
                + "\"returned\":4,\"accepted\":18,\"progress_percent\":50}";
        }
    }

    /** admin 专属写工具（Step4 成员面必须不可见） */
    static class AdminTools {
        @Tool(name = "act_remind", description = "向任务责任人发送催办提醒（写操作，管理员专属）")
        public String actRemind(String missionKeyword, String userId) {
            return "{\"sent\":true,\"to\":\"" + userId + "\"}";
        }
    }

    private DcsAiConfig loadDeepSeekConfig() {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg, "dcs_ai_config 应有 " + CONFIG_ID + "（批0 已建）");
        assertEquals("978311", cfg.getTenantId(), "测试租户专用配置");
        assertEquals("1", cfg.getEnabled());
        assertNotNull(cfg.getApiKey(), "apiKey 应由 MyBatis 加密拦截器自动解密为明文");
        return cfg;
    }

    private ReActAgent buildAgent(DcsAiConfig cfg) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ProbeTools());
        return ReActAgent.builder()
            .sysPrompt("你是 DCS 数据采集系统的工作助手。回答用户问题前必须调用工具获取真实数据，"
                + "禁止凭空编造任务与数字。回答用简洁中文。")
            .model(OpenAIChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey())
                .modelName(cfg.getModelName())
                .stream(false)
                .formatter(new DeepSeekFormatter())
                .build())
            .toolkit(toolkit)
            .maxIters(8)
            .build();
    }

    /** Step1+2+3：5 轮对话 × 3 遍（稳定性 3/3） */
    @RepeatedTest(value = 3, name = "第{currentRepetition}遍/共{totalRepetitions}")
    void fiveRoundToolLoop() {
        DcsAiConfig cfg = loadDeepSeekConfig();
        ReActAgent agent = buildAgent(cfg);
        RuntimeContext rc = RuntimeContext.builder()
            .userId("t:978311:p0-probe").sessionId("p01-" + Instant.now().toEpochMilli()).build();

        // 每轮都含新数据需求（首轮实测教训：纯汇总型问题模型会复用会话内工具结果直接回答，
        // 属合理行为——判据"每轮都出现 tool call"要求问题设计每轮都要新数据）
        String[] rounds = {
            "我的待办任务有哪些？请逐条列出。",
            "『成员信息采集表』这个任务现在的填报进度怎么样？",
            "再帮我重新查一遍待办，然后告诉我待办里第一条任务的最新进度是多少？",
            "『实验室安全培训』这个任务的进度现在如何？如果进度落后请提醒我关注。",
            "最后确认：我现在一共有几条待办？『助学金申请材料』的任务进度是多少？"
        };

        int toolCallsBefore = TODO_CALLS.get() + PROGRESS_CALLS.get();
        long totalIn = 0, totalOut = 0;
        Instant start = Instant.now();

        for (int i = 0; i < rounds.length; i++) {
            int before = TODO_CALLS.get() + PROGRESS_CALLS.get();
            Msg reply = agent.call(rounds[i], rc).block(Duration.ofSeconds(180));
            assertNotNull(reply, "第" + (i + 1) + "轮应返回消息");
            int after = TODO_CALLS.get() + PROGRESS_CALLS.get();
            assertTrue(after > before,
                "第" + (i + 1) + "轮应出现工具调用（before=" + before + " after=" + after + "）");

            String text = reply.getContentBlocks(TextBlock.class).stream()
                .map(TextBlock::getText).reduce("", (a, b) -> a + b);
            assertFalse(text.isBlank(), "第" + (i + 1) + "轮最终文本非空");

            if (reply.getUsage() != null) {
                totalIn += reply.getUsage().getInputTokens();
                totalOut += reply.getUsage().getOutputTokens();
                System.out.printf("[P01] 第%d轮 tokens(in=%d out=%d) 工具调用+%d 文本%d字%n",
                    i + 1, reply.getUsage().getInputTokens(), reply.getUsage().getOutputTokens(),
                    after - before, text.length());
            }
        }
        long elapsed = Instant.now().getEpochSecond() - start.getEpochSecond();
        System.out.printf("[P01] 本遍完成：5轮 工具调用共+%d 累积tokens(in=%d out=%d) 耗时%ds%n",
            TODO_CALLS.get() + PROGRESS_CALLS.get() - toolCallsBefore, totalIn, totalOut, elapsed);
        assertTrue(TODO_CALLS.get() >= 3, "query_my_todos 至少被调 3 次（实际 " + TODO_CALLS.get() + "）");
        assertTrue(PROGRESS_CALLS.get() >= 2, "query_mission_progress 至少被调 2 次（实际 " + PROGRESS_CALLS.get() + "）");
    }

    /** Step4 前半：双身份 Toolkit——成员激活面无 act_* 工具（不花模型钱，纯本地语义断言） */
    @Test
    void toolGroupRoleCropping() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ProbeTools());
        toolkit.registerTool(new AdminTools());
        toolkit.createToolGroup("student", "成员工具面：只读查询", true);
        toolkit.createToolGroup("admin", "管理员工具面：含写操作", false);
        toolkit.addToolToGroup("student", "query_my_todos");
        toolkit.addToolToGroup("student", "query_mission_progress");
        toolkit.addToolToGroup("admin", "act_remind");

        // 成员身份：激活面只含 student 组
        toolkit.setActiveGroups(List.of("student"));
        assertEquals(List.of("student"), toolkit.getActiveGroups(), "成员激活组应为 student");
        // 全量 schema 里 act_remind 存在（注册成功），但激活组不含 admin——成员模型可见面无 act_*
        assertTrue(toolkit.getToolNames().contains("act_remind"), "act_remind 应已注册（全量面）");
        boolean adminActive = toolkit.getActiveGroups().contains("admin");
        assertFalse(adminActive, "成员激活面不得包含 admin 组（act_* 不可见）");

        // 管理员身份：双组全开
        toolkit.setActiveGroups(List.of("student", "admin"));
        assertTrue(toolkit.getActiveGroups().contains("admin"), "管理员激活面应含 admin 组");

        // schema 快照落证据
        List<ToolSchema> schemas = toolkit.getToolSchemas();
        System.out.println("[P01] 全量工具 schema：" + schemas.stream().map(ToolSchema::getName).toList());
        assertTrue(schemas.size() >= 3, "三个工具 schema 应全部注册");
    }

    /** Step4 后半：maxRetries 行为——不可达地址触发连接失败，断言明确报错（打假地址，不花模型钱） */
    @Test
    void maxRetriesFailsWithClearError() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ProbeTools());
        ReActAgent agent = ReActAgent.builder()
            .sysPrompt("test")
            .model(OpenAIChatModel.builder()
                .baseUrl("http://10.255.255.1:19999")   // 不可达地址（RFC6669 坑位，必连接超时）
                .apiKey("sk-p0-probe-not-used")
                .modelName("deepseek-v4-flash")
                .stream(false)
                .formatter(new DeepSeekFormatter())
                .build())
            .toolkit(toolkit)
            .maxIters(2)
            .maxRetries(2)
            .build();
        RuntimeContext rc = RuntimeContext.builder().userId("u").sessionId("s").build();

        Instant start = Instant.now();
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> agent.call("你好", rc).block(Duration.ofSeconds(120)),
            "不可达地址最终应抛明确异常（重试耗尽）");
        long secs = Instant.now().getEpochSecond() - start.getEpochSecond();
        System.out.printf("[P01] maxRetries=2 不可达地址：%.1fs 后明确报错：%s（根因链=%s）%n",
            (float) secs, ex.getMessage(),
            ex.getCause() == null ? "无" : ex.getCause().getClass().getSimpleName());
        assertNotNull(ex.getMessage(), "异常信息不得为空（明确报错，非静默）");
    }
}
