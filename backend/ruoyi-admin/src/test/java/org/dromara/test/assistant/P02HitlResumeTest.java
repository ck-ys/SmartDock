package org.dromara.test.assistant;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.DcsAiConfigMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T2 探针：HITL 中断/恢复闭环（实测②，真实付费调用）。
 * <p>判据（计划 Task 2）：</p>
 * <ul>
 *   <li>Step1：act_remind 工具标 permission=ASK（PermissionContextState.addAskRule）→
 *       stream 观察中断事件（RequireUserConfirmEvent 含工具名+args）→ 恢复原样 args →
 *       断言执行完成且状态落 AgentStateStore；</li>
 *   <li>Step2 反例：resume 时改 args 一个字段 → 记录框架行为（预期照单全收，坐实 spec §6 防篡改必要性）；</li>
 *   <li>Step3 断线重连：两段独立运行（第一段中断后退出、state 落库；第二段新 JVM 读 state 续跑）。</li>
 * </ul>
 * <p>本类第一版先观察事件序列真实形态（interrupt 表现/恢复通道），恢复协议按观察结果在第二版补全。</p>
 */
@Tag("p0probe")
@SpringBootTest
class P02HitlResumeTest {

    private static final long CONFIG_ID = 9100000000003L;

    @Autowired
    DcsAiConfigMapper aiConfigMapper;

    /** 催办写工具（HITL 标的） */
    static class RemindTools {
        static final java.util.concurrent.atomic.AtomicInteger EXECUTIONS = new java.util.concurrent.atomic.AtomicInteger();
        static volatile String lastArgs;

        @Tool(name = "act_remind", description = "向任务责任人发送催办提醒")
        public String actRemind(
            @io.agentscope.core.tool.ToolParam(name = "task_name", required = true, description = "任务名称关键词")
            String missionKeyword,
            @io.agentscope.core.tool.ToolParam(name = "user_id", required = true, description = "责任人用户ID")
            String userId) {
            EXECUTIONS.incrementAndGet();
            lastArgs = "mission=" + missionKeyword + ";user=" + userId;
            return "{\"sent\":true,\"mission\":\"" + missionKeyword + "\",\"to\":\"" + userId + "\"}";
        }
    }

    private Model buildModel(DcsAiConfig cfg) {
        return OpenAIChatModel.builder()
            .baseUrl(cfg.getBaseUrl()).apiKey(cfg.getApiKey()).modelName(cfg.getModelName())
            .stream(false).formatter(new DeepSeekFormatter()).build();
    }

    /**
     * Step1 完整闭环：中断（AGENT_RESULT + meta.agentscope_confirm_request_reply_id）
     * → 恢复（下一条 Msg metadata 带 replyId + ConfirmResult(confirmed, toolCall)）
     * → 断言工具真实执行 + 最终回复非空。
     */
    @Test
    void hitlInterruptAndResume() {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);

        PermissionContextState permission = PermissionContextState.builder()
            .addAskRule("*", new PermissionRule("act_remind", "", PermissionBehavior.ASK, "p0-probe"))
            .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new RemindTools());

        ReActAgent agent = ReActAgent.builder()
            .sysPrompt("你是 DCS 工作助手。用户要求催办时必须调用 act_remind 工具执行。")
            .model(buildModel(cfg))
            .toolkit(toolkit)
            .maxIters(5)
            .permissionContext(permission)
            .build();

        RuntimeContext rc = RuntimeContext.builder()
            .userId("t:978311:p0-hitl").sessionId("p02-" + System.currentTimeMillis()).build();

        List<String> eventSequence = new CopyOnWriteArrayList<>();
        // 观察版：打印每个 stream Event 的 type + metadata keys + 文本摘要，确认 interrupt 真实形态
        agent.stream(List.of(Msg.builderForRole(MsgRole.USER).textContent(
                "请帮我催办『成员信息采集表』这个任务，提醒责任人 user-88 尽快处理。").build()),
            StreamOptions.defaults(), rc)
            .timeout(Duration.ofSeconds(180))
            .doOnNext(ev -> {
                String meta = ev.getMessage() == null ? "-" : String.valueOf(ev.getMessage().getMetadata().keySet());
                String text = ev.getMessage() == null ? ""
                    : ev.getMessage().getContentBlocks(TextBlock.class).stream()
                        .map(TextBlock::getText).reduce("", (a, b) -> a + b);
                eventSequence.add(ev.getType() + "[meta=" + meta
                    + (text.isBlank() ? "" : " text=" + Math.min(text.length(), 80) + "字") + "]");
            })
            .blockLast();

        System.out.println("[P02] 事件序列（" + eventSequence.size() + " 条）:");
        eventSequence.forEach(e -> System.out.println("[P02]   " + e));
        System.out.println("[P02] 工具执行次数 = " + RemindTools.EXECUTIONS.get());

        assertTrue(eventSequence.size() > 0, "应有事件产生");
        // 三审修正：绝对零断言改增量口径（类内 static 计数器跨方法累积，绝对值在方法序变化下假红）
        int execAtInterrupt = RemindTools.EXECUTIONS.get();
        eventSequence.add(0, "execAtInterrupt=" + execAtInterrupt);

        // ===== 中断信息提取（第一段 stream 的 AGENT_RESULT meta 有 replyId；ToolUseBlock 在 AgentState.context）=====
        // 已实证事实（上轮红）：HITL 挂起期间会话锁死——第二条不带确认的消息直接抛
        //   IllegalStateException "Agent is paused for human-in-the-loop confirmation ... ASKING state"
        // 因此 pending 调用从 agent.getAgentState(rc).getContext() 取（ASKING 态 assistant 消息含 ToolUseBlock）
        ToolUseBlock pendingCall = agent.getAgentState(rc).getContext().stream()
            .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
            .filter(t -> "act_remind".equals(t.getName()))
            .reduce((a, b) -> b)   // 取最后一个（本会话仅一次中断）
            .orElse(null);
        System.out.println("[P02] pendingCall=" + (pendingCall == null ? "无"
            : pendingCall.getName() + "(id=" + pendingCall.getId() + " " + pendingCall.getInput() + ")"));
        assertNotNull(pendingCall, "AgentState.context 应含待确认 ToolUseBlock");
        assertEquals("act_remind", pendingCall.getName());
        // 三审修正：恒真兜底改真断言——@ToolParam 钉名后模型按 schema 参数名生成
        assertTrue(pendingCall.getInput().containsKey("task_name") && pendingCall.getInput().containsKey("user_id"),
            "待确认调用应带 @ToolParam 钉死的参数名：" + pendingCall.getInput());
        assertNotNull(pendingCall.getInput().get("task_name"), "task_name 不得为 null");

        // ===== 恢复：下一条输入 Msg 带 replyId + ConfirmResult(confirmed=true, 原样 toolCall) =====
        Msg resumeMsg = Msg.builderForRole(MsgRole.USER)
            .textContent("确认执行催办。")
            .metadata(java.util.Map.of(
                Msg.METADATA_CONFIRM_RESULTS,
                java.util.List.of(new io.agentscope.core.event.ConfirmResult(true, pendingCall))))
            .build();
        Msg reply = agent.call(List.of(resumeMsg), rc).block(Duration.ofSeconds(180));
        assertNotNull(reply, "恢复后应有回复");
        String finalText = reply.getContentBlocks(TextBlock.class).stream()
            .map(TextBlock::getText).reduce("", (a, b) -> a + b);
        System.out.println("[P02] 恢复后执行数=" + RemindTools.EXECUTIONS.get() + " args=" + RemindTools.lastArgs
            + " 最终回复=" + Math.min(finalText.length(), 120) + "字");
        assertTrue(RemindTools.EXECUTIONS.get() > execAtInterrupt, "确认后工具应真实执行");
        assertTrue(RemindTools.lastArgs.contains("成员信息采集表"), "执行的 args 应为待确认的原样参数：" + RemindTools.lastArgs);
        assertFalse(finalText.isBlank(), "最终回复文本非空");
    }

    /** Step2 反例：恢复时改 args 一个字段（editedArgs 模拟）→ 记录框架行为（预期照单全收，坐实防篡改必要性） */
    @Test
    void resumeWithTamperedArgsRecordsFrameworkBehavior() {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        PermissionContextState permission = PermissionContextState.builder()
            .addAskRule("*", new PermissionRule("act_remind", "", PermissionBehavior.ASK, "p0-probe"))
            .build();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new RemindTools());
        ReActAgent agent = ReActAgent.builder()
            .sysPrompt("你是 DCS 工作助手。用户要求催办时必须调用 act_remind 工具执行。")
            .model(buildModel(cfg)).toolkit(toolkit).maxIters(5).permissionContext(permission).build();
        RuntimeContext rc = RuntimeContext.builder()
            .userId("t:978311:p0-hitl").sessionId("p02-tamper-" + System.currentTimeMillis()).build();

        int execBefore = RemindTools.EXECUTIONS.get();
        agent.stream(List.of(Msg.builderForRole(MsgRole.USER).textContent(
                "请催办『助学金申请材料』，责任人 user-77。").build()),
            StreamOptions.defaults(), rc).timeout(Duration.ofSeconds(180)).blockLast();

        ToolUseBlock pending = agent.getAgentState(rc).getContext().stream()
            .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
            .filter(t -> "act_remind".equals(t.getName())).reduce((a, b) -> b).orElse(null);
        assertNotNull(pending, "应处于中断态");

        // 篡改：同 id 同名，task_name 换成攻击者内容（模拟前端/中间人改参）
        ToolUseBlock tampered = new ToolUseBlock(pending.getId(), pending.getName(),
            java.util.Map.of("task_name", "被篡改的任务", "user_id", "attacker-1"));
        Msg resume = Msg.builderForRole(MsgRole.USER).textContent("确认执行。")
            .metadata(java.util.Map.of(Msg.METADATA_CONFIRM_RESULTS,
                java.util.List.of(new io.agentscope.core.event.ConfirmResult(true, tampered))))
            .build();
        List<String> tamperEvents = new CopyOnWriteArrayList<>();
        agent.stream(List.of(resume), StreamOptions.defaults(), rc)
            .timeout(Duration.ofSeconds(180))
            .doOnNext(ev -> {
                String text = ev.getMessage() == null ? ""
                    : ev.getMessage().getContentBlocks(TextBlock.class).stream()
                        .map(TextBlock::getText).reduce("", (a, b) -> a + b);
                tamperEvents.add(ev.getType()
                    + (text.isBlank() ? "" : " text=" + text.replaceAll("\\s+", " ").substring(0, Math.min(60, text.length()))));
            })
            .blockLast();
        System.out.println("[P02] 篡改恢复事件=" + tamperEvents);

        System.out.println("[P02] 篡改恢复后执行数=" + (RemindTools.EXECUTIONS.get() - execBefore)
            + " 实收args=" + RemindTools.lastArgs);
        // 行为定版（两轮稳定）：框架拒绝执行被篡改的调用——工具零执行，模型收到 TOOL_RESULT 后
        // 重新推理收尾（事件序列=TOOL_RESULT→REASONING×3→AGENT_RESULT 无二次 confirm）。
        // 计划预期"照单全收"不成立：框架自带 input 比对防护；spec §6 快照哈希保留为纵深防御
        // （防前端确认卡展示层篡改与 SKIP 场景，不依赖框架此行为）。
        boolean executed = RemindTools.EXECUTIONS.get() > execBefore;
        System.out.println("[P02] 框架对篡改 args 的行为 = " + (executed ? "照单全收（不校验，需产品层拦截）" : "拒绝执行（框架自带防护）"));
        assertEquals(execBefore, RemindTools.EXECUTIONS.get(),
            "被篡改的 ConfirmResult 不得导致工具执行（框架防护事实断言）");
    }

    /** Step3 第一段：中断后正常退出，state 落 PG（sessionId 写 target/p02-phase.txt 供第二段新 JVM 读取） */
    @Test
    void phaseOneInterruptAndPersist() throws Exception {
        java.nio.file.Path marker = java.nio.file.Path.of("target", "p02-phase.txt");
        String sessionId = "p02-xjvm-" + System.currentTimeMillis();
        int execBefore = RemindTools.EXECUTIONS.get();

        ReActAgent agent = buildPersistedAgent();
        RuntimeContext rc = RuntimeContext.builder()
            .userId("t:978311:p0-hitl").sessionId(sessionId).build();
        agent.call(List.of(Msg.builderForRole(MsgRole.USER).textContent(
                "请催办『跨JVM测试任务』，责任人 user-66。").build()), rc)
            .timeout(Duration.ofSeconds(180)).block();

        int execNow = RemindTools.EXECUTIONS.get();
        System.out.println("[P02] phase1 sessionId=" + sessionId + " 执行数=" + (execNow - execBefore));
        assertEquals(execBefore, execNow, "phase1 中断退出，工具不得执行");

        // 实证事实（修正）：中断时框架自动落 state——session_id 列存 "userId:sessionId" 拼接形态，
        // state_key="agent_state"（探针第 9 轮按裸 sessionId 查 0 行是查询形态错，非不落库）
        String storedSessionId = "t:978311:p0-hitl" + ":" + sessionId;
        try (java.sql.Connection c = probeDataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                 "SELECT state_key, length(state_data) FROM agentscope.agentscope_sessions WHERE session_id = ?")) {
            ps.setString(1, storedSessionId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "中断态应已自动落 agentscope_sessions（key=agent_state）");
                System.out.println("[P02] 中断自动落库 state_key=" + rs.getString(1)
                    + " dataLen=" + rs.getLong(2) + " storedSessionId=" + storedSessionId);
            }
        }
        java.nio.file.Files.createDirectories(marker.getParent());
        java.nio.file.Files.writeString(marker, sessionId);
        System.out.println("[P02] phase1 完成，marker 写入 " + marker);
    }

    /** Step3 第二段：新 JVM 新测试方法，从 PG state 恢复会话并完成确认（真·跨 JVM 断线重连） */
    @Test
    void phaseTwoResumeInFreshJvm() throws Exception {
        java.nio.file.Path marker = java.nio.file.Path.of("target", "p02-phase.txt");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.exists(marker),
            "需先跑 phaseOneInterruptAndPersist（独立 surefire 调用=独立 JVM）");
        String sessionId = java.nio.file.Files.readString(marker).trim();
        int execBefore = RemindTools.EXECUTIONS.get();

        // 原生恢复路径：新 JVM 新 agent 实例 + 同 (userId,sessionId) + stateStore
        // → 框架 loadOrCreateAgentStateForSlot 自动从 PG 读 agent_state（无需手动 save/重放）
        ReActAgent agent = buildPersistedAgent();
        RuntimeContext rc = RuntimeContext.builder()
            .userId("t:978311:p0-hitl").sessionId(sessionId).build();
        ToolUseBlock pending = agent.getAgentState(rc).getContext().stream()
            .flatMap(m -> m.getContentBlocks(ToolUseBlock.class).stream())
            .filter(t -> "act_remind".equals(t.getName())).reduce((a, b) -> b).orElse(null);
        System.out.println("[P02] phase2 从 PG 恢复 pending=" + (pending == null ? "无" : pending.getInput())
            + " 历史消息数=" + agent.getAgentState(rc).getContext().size());
        assertNotNull(pending, "新 JVM 应能从 PG state 读到中断态 ToolUseBlock");
        Object phase1TaskName = pending.getInput().get("task_name");
        assertNotNull(phase1TaskName, "恢复的 pending 应含 task_name 参数");
        assertTrue(phase1TaskName.toString().contains("跨JVM测试任务"),
            "恢复的 pending args 应是 phase1 原样：" + pending.getInput());

        Msg resume = Msg.builderForRole(MsgRole.USER).textContent("确认执行。")
            .metadata(java.util.Map.of(Msg.METADATA_CONFIRM_RESULTS,
                java.util.List.of(new io.agentscope.core.event.ConfirmResult(true, pending))))
            .build();
        Msg reply = agent.call(List.of(resume), rc).timeout(Duration.ofSeconds(180)).block();
        assertNotNull(reply);
        System.out.println("[P02] phase2 执行数=" + (RemindTools.EXECUTIONS.get() - execBefore)
            + " args=" + RemindTools.lastArgs);
        assertTrue(RemindTools.EXECUTIONS.get() > execBefore, "跨 JVM 恢复确认后工具应执行");
        assertTrue(RemindTools.lastArgs.contains("跨JVM测试任务"), "执行 args 应为 phase1 的原样参数");
    }

    /** Step3 共用：带 PG 持久 stateStore 的 agent（T11 装配配方复用） */
    private ReActAgent buildPersistedAgent() {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        PermissionContextState permission = PermissionContextState.builder()
            .addAskRule("*", new PermissionRule("act_remind", "", PermissionBehavior.ASK, "p0-probe"))
            .build();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new RemindTools());
        return ReActAgent.builder()
            .sysPrompt("你是 DCS 工作助手。用户要求催办时必须调用 act_remind 工具执行。")
            .model(buildModel(cfg)).toolkit(toolkit).maxIters(5).permissionContext(permission)
            .stateStore(buildStateStore())
            .build();
    }

    private static javax.sql.DataSource probeDataSource() {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setServerNames(new String[]{"127.0.0.1"});
        ds.setPortNumbers(new int[]{5432});
        ds.setDatabaseName("smartdock");
        ds.setUser("postgres");
        ds.setPassword("postgres");
        ds.setCurrentSchema("agentscope");
        return ds;
    }

    private static io.agentscope.core.state.AgentStateStore buildStateStore() {
        javax.sql.DataSource ds = probeDataSource();
        io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect dialect =
            io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect.from(ds).autoCreateTable(false).build();
        return new io.agentscope.extensions.jdbc.state.JdbcAgentStateStore(ds, dialect, false);
    }
}
