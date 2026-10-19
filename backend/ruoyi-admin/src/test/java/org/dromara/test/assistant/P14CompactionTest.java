package org.dromara.test.assistant;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.DcsAiConfigMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T14 探针：压缩与卸载实测（实测⑭，0923 A1/A2 拍板新增）。
 * <p>判据（计划 Task 14，含判据修订）：</p>
 * <ul>
 *   <li>Step1/2：CompactionMiddleware（triggerTokens 小值）+ 探针会话灌多轮长消息触发压缩；
 *       摘要中业务标识符原值存活（missionId/口径结论）；红线文本位于系统提示层=压缩免疫（中间件只动上下文消息，sysPrompt 不在压缩面）；</li>
 *   <li>Step3：&gt;80K 字符工具结果 → 占位符（头尾预览+路径引用）+ 全文可取回；</li>
 *   <li>Step4（判据修订）：**recoverFromOverflow 在 AgentScope 2.0.3 不存在**（三类 jar 类名/常量池/字符串三重检索零命中）——
 *       实测塞爆窗口的真实行为（错误显式传播非挂死），P1 兜底方案=装配层捕获二次压缩重试（结论进证据与 spec 回填）；</li>
 *   <li>Step5：阈值钳制双配置对照（in-memory context_window=8000 断言被钳；live 1M 断言不被钳）；</li>
 *   <li>附加①：真实知识包+60K 档（T5b 产出喂入，input token 实测+时延记录，定 60K/128K/256K 档位结论）；</li>
 *   <li>附加②（X-05）：卸载文件跨实例可读（PG 双 BaseStore 双实例读写一致，防未映射路径静默落本机）。</li>
 * </ul>
 * <p>预算纪律：本类约 8-10 次模型调用（压缩摘要×2、e2e 回合×3、60K 档×1、溢出拒绝×1）。</p>
 */
@Tag("p0probe")
@SpringBootTest
class P14CompactionTest {

    private static final long CONFIG_ID = 9100000000003L;
    private static final String MISSION_ID = "M-2097573789689737217";
    private static final String CALIBER = "广播任务发布时不建 trace 是常态";
    private static final String RED_LINE = "成员身份禁止发起催办";

    @Autowired
    DcsAiConfigMapper aiConfigMapper;

    /** 清理口径：仅删本探针 namespace 的 PG 行（X-05 写入），schema/表保留 */
    @org.junit.jupiter.api.AfterAll
    static void cleanupX05Rows() throws Exception {
        try (Connection c = controlledDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM agentscope.agentscope_store WHERE namespace_path = ?")) {
            ps.setString(1, "p0-probe-x05\u001F");
            ps.executeUpdate();
        }
    }

    @TempDir
    Path workspaceDir;

    private Model buildModel(DcsAiConfig cfg) {
        return OpenAIChatModel.builder()
            .baseUrl(cfg.getBaseUrl()).apiKey(cfg.getApiKey()).modelName(cfg.getModelName())
            .stream(false).formatter(new DeepSeekFormatter()).build();
    }

    private static Msg user(String text) {
        return Msg.builderForRole(MsgRole.USER).textContent(text).build();
    }

    private static Msg assistant(String text) {
        return Msg.builderForRole(MsgRole.ASSISTANT).textContent(text).build();
    }

    private static String textOf(Msg m) {
        return String.join("", m.getContentBlocks(TextBlock.class).stream().map(TextBlock::getText).toList());
    }

    /** 造一段约 n 字符的中文填充文本（含编号防缓存全命中干扰摘要） */
    private static String filler(int chars, String tag) {
        StringBuilder sb = new StringBuilder(chars + 64);
        int i = 0;
        while (sb.length() < chars) {
            sb.append("第").append(i++).append("条：").append(tag)
              .append("日常采集数据样例——部门应急联络名单更新、实验室安全培训签到、")
              .append("助学金申请材料复核与归档，责任人在截止时间前完成填报并提交审核。\n");
        }
        return sb.toString();
    }

    // ================= Step5：阈值钳制双配置对照（纯算术，零模型） =================

    @Test
    void thresholdClampDualConfig() throws Exception {
        // 语义实测修正（判据修订）：withEffective(contextWindow, keepTokens) 是【直覆盖】不是钳制——
        // triggerTokens←arg1、keepTokens←arg2（三审 glm53 字节码复核证实）。计划预期"框架钳制"不成立。
        CompactionConfig base = CompactionConfig.builder().triggerTokens(60000).keepTokens(4000).build();
        CompactionConfig small = base.withEffective(8000, 500);
        assertEquals(8000, small.getTriggerTokens(), "withEffective arg1 直覆盖 triggerTokens（非钳制）");
        assertEquals(500, small.getKeepTokens(), "withEffective arg2 直覆盖 keepTokens");

        // P1 装配层钳制公式【预演】（三审修正：此处不做 Math.min 结果自断言的同义反复——
        // 生效值的真回合证据见 middlewareE2e 配对：同 trigger=600，keepTokens 未设(默认-1)=压缩静默跳过，
        // keepTokens=1500=压缩触发，配置生效性由该配对实证；本方法只锁定公式输入的窗口值来源）
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        Model model = buildModel(cfg);
        int ctxSize = model.getContextWindowSize();
        System.out.println("[P14] model.getContextWindowSize() = " + ctxSize
            + "（0=模型未上报→窗口值不能靠模型接口，P1 须 dcs_assistant_config 配置兜底）");
        assertEquals(0, ctxSize, "deepseek-v4-flash 经 OpenAIChatModel 不上报窗口（实测锁定：P1 配置兜底为唯一路径）");
    }

    // ================= 附加②（X-05）：卸载文件跨实例可读（PG 双实例，零模型） =================

    @Test
    void offloadFileCrossInstanceViaPg() throws Exception {
        BaseStore store1 = buildBaseStore();
        BaseStore store2 = buildBaseStore();   // 第二实例：独立连接独立对象，模拟跨实例读取
        List<String> ns = List.of("p0-probe-x05");
        RuntimeContext rc = RuntimeContext.builder()
            .userId("t:978311:p14x05").sessionId("x05-" + System.currentTimeMillis()).build();

        String big = "X05-HEAD\n" + filler(20000, "x05") + "\nX05-TAIL";
        RemoteFilesystem fs1 = new RemoteFilesystem(store1, ns);
        // 坑65：RemoteFilesystem write 存裸键、exists/read 走 normalizePath（非 / 开头会被加前导斜杠）——
        // 写读不对称。统一用前导斜杠路径（normalize 恒等形态）绕开。
        var w = fs1.write(rc, "/offload/big-report.txt", big);
        System.out.println("[P14] X-05 write isSuccess=" + w.isSuccess()
            + " path=" + w.path() + " error=" + w.error());
        assertTrue(w.isSuccess(), "实例1写入应成功（error=" + w.error() + "）");
        assertTrue(fs1.exists(rc, "/offload/big-report.txt"), "实例1写入后应可见（前导斜杠=规范化恒等）");

        RemoteFilesystem fs2 = new RemoteFilesystem(store2, ns);
        // 行号 0 基（实测 read(1,5) 从第2行起）——头段读 0 起；尾段用超界行区间取尾
        ReadResult back = fs2.read(rc, "/offload/big-report.txt", 0, 4);
        assertTrue(back.isSuccess(), "跨实例读取应成功（" + back.error() + "）");
        String head = back.fileData().content();
        System.out.println("[P14] X-05 跨实例回读前80字 = "
            + head.substring(0, Math.min(80, head.length())).replace('\n', '|'));
        assertTrue(head.contains("X05-HEAD"), "跨实例应读到文件头（路径经 PG 映射，未静默落本机）");
        // 尾段：按运行时实际行数取最后一行（超界起始行会报错，硬编码行号脆弱）
        int lastLine = big.split("\n").length - 1;
        ReadResult tail = fs2.read(rc, "/offload/big-report.txt", lastLine, lastLine);
        assertTrue(tail.isSuccess(), "跨实例尾段读取应成功（" + tail.error() + "）");
        String tailText = tail.fileData().content();
        System.out.println("[P14] X-05 跨实例尾段读取 = "
            + tailText.substring(0, Math.min(120, tailText.length())).replace('\n', '|'));
        assertTrue(tailText.contains("X05-TAIL"), "行区间取回应能读到全文尾标记（卸载往返完整性）");
    }

    private static DataSource controlledDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{"127.0.0.1"});
        ds.setPortNumbers(new int[]{5432});
        ds.setDatabaseName("smartdock");
        ds.setUser("postgres");
        ds.setPassword("postgres");
        ds.setCurrentSchema("agentscope");
        return ds;
    }

    private static BaseStore buildBaseStore() {
        DataSource ds = controlledDataSource();
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).autoCreateTable(false).build();
        return JdbcStore.builder(ds).dialect(dialect).initializeSchema(false).build();
    }

    // ================= Step1/2：压缩触发 + 摘要质量（ConversationCompactor 直测，1 次摘要调用） =================

    @Test
    void compactorSummaryKeepsBusinessMarkers() throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        Model model = buildModel(cfg);
        // WorkspaceManager 持 SQLite 索引句柄——全程 try/finally close（三审 P2 修正）
        WorkspaceManager wm = new WorkspaceManager(workspaceDir);
        try {
            MemoryFlushManager flush = new MemoryFlushManager(wm, model);
            ConversationCompactor compactor = new ConversationCompactor(model, flush);

            // 造 10 轮长对话（远超 triggerTokens=800）：第 3 轮预埋业务标识符
            List<Msg> msgs = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                if (i == 3) {
                    msgs.add(user("任务 " + MISSION_ID + " 的口径结论是什么？"));
                    msgs.add(assistant(CALIBER + "。myTraceIds 恒空属正常，不要当故障上报。"));
                } else {
                    msgs.add(user("第" + i + "轮：请记录部门应急联络名单第" + i + "批更新。"));
                    msgs.add(assistant(filler(400, "批次" + i)));
                }
            }
            RuntimeContext rc = RuntimeContext.builder()
                .userId("t:978311:p14").sessionId("p14-direct-" + System.currentTimeMillis()).build();
            CompactionConfig config = CompactionConfig.builder()
                .triggerTokens(800).keepMessages(2).build();

            Optional<List<Msg>> out = compactor.compactIfNeeded(rc, msgs, config,
                "t:978311:p14", "p14-direct").block(Duration.ofSeconds(120));
            assertNotNull(out, "compactIfNeeded 应返回（非 null）");
            assertTrue(out.isPresent(), "超过阈值应触发压缩（返回新上下文）");
            List<Msg> compacted = out.get();
            System.out.println("[P14] 压缩后消息数 = " + compacted.size() + "（原 " + msgs.size() + "）");
            assertTrue(compacted.size() < msgs.size(), "压缩后上下文必须收缩");

            String allText = compacted.stream().map(P14CompactionTest::textOf).reduce("", (a, b) -> a + b);
            assertTrue(allText.contains(MISSION_ID), "摘要必须保留业务标识符原值（missionId）");
            assertTrue(allText.contains("广播") || allText.contains("trace"), "摘要必须保留口径结论要义");
            System.out.println("[P14] 摘要含 missionId=" + allText.contains(MISSION_ID) + "，样本="
                + allText.substring(0, Math.min(200, allText.length())).replace('\n', '|'));
        } finally {
            wm.close();
        }
    }

    // ================= Step1 e2e + Step3 卸载 + Step4 续跑：三合一（Compaction+Eviction 双中间件真链路） =================

    /** 大结果工具：85K 字符（>80K 默认阈值），头尾埋标记，中段含 missionId */
    static class BigReportTools {
        static final String BIG_HEAD = "EVICT-HEAD-MARKER";
        static final String BIG_TAIL = "EVICT-TAIL-MARKER";

        @Tool(name = "big_report", description = "导出指定任务的全量明细报告（大文本）")
        public String bigReport(@io.agentscope.core.tool.ToolParam(name = "mission_keyword", required = true,
                description = "任务名关键词") String missionKeyword) {
            return BIG_HEAD + "\n任务" + MISSION_ID + "（" + missionKeyword + "）全量明细：\n"
                + filler(85000, "明细") + "\n" + BIG_TAIL;
        }

        @Tool(name = "small_note", description = "查询一条备注（小结果）")
        public String smallNote() {
            return "备注：口径=" + CALIBER;
        }
    }

    @Test
    void middlewareE2eEvictionCompactionAndContinuity() throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        Model model = buildModel(cfg);
        // 坑67：WorkspaceManager(Path) 单参构造 getFilesystem() 返回 null（惰性字段不初始化）——
        // eviction 中间件持 null filesystem 静默失败（只 WARN 不抛）。必须显式传 LocalFilesystem。
        io.agentscope.harness.agent.filesystem.local.LocalFilesystem localFs =
            new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(workspaceDir);
        WorkspaceManager wm = new WorkspaceManager(workspaceDir, localFs);

        CompactionConfig compCfg = CompactionConfig.builder()
            .triggerTokens(600).keepMessages(2).keepTokens(1500).build();
        ToolResultEvictionMiddleware eviction = new ToolResultEvictionMiddleware(
            localFs, ToolResultEvictionConfig.defaults());

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new BigReportTools());

        String sysPrompt = "你是 DCS 数据采集系统的工作助手。红线：" + RED_LINE
            + "。回答用简洁中文，需要数据时必须调用工具。";
        ReActAgent agent = ReActAgent.builder()
            .name("p0-evict-agent")   // 坑66：eviction 路径清洗用 agent.getName()，不命名=NPE 静默失败
            .sysPrompt(sysPrompt)
            .model(model)
            .toolkit(toolkit)
            .middleware(new CompactionMiddleware(wm, model, compCfg))
            .middleware(eviction)
            .maxIters(6)
            .build();
        RuntimeContext rc = RuntimeContext.builder()
            .userId("t:978311:p14").sessionId("p14-e2e-" + System.currentTimeMillis()).build();

        try {
        // 回合1：大报告（85K 工具结果 → eviction 应在下一推理前卸载成占位符）
        Msg r1 = agent.call(List.of(user("请调用 big_report 工具导出『联络名单』的报告，"
            + "然后只告诉我报告开头和结尾的标记词是什么。")), rc)
            .block(Duration.ofSeconds(180));
        assertNotNull(r1);
        String t1 = textOf(r1);
        System.out.println("[P14] 回合1应答 = " + t1.substring(0, Math.min(120, t1.length())));

        // 工具结果在 ToolResultBlock（textOf 只读 TextBlock 会判据空转——坑66 附带教训）
        List<Msg> ctx1 = new ArrayList<>(agent.getAgentState(rc).getContext());
        String bigReportCtx = ctx1.stream()
            .flatMap(m -> m.getContentBlocks(io.agentscope.core.message.ToolResultBlock.class).stream())
            .filter(b -> "big_report".equals(b.getName()))
            .flatMap(b -> b.getOutput().stream())
            .filter(tb -> tb instanceof TextBlock)
            .map(tb -> ((TextBlock) tb).getText())
            .reduce("", (a, b) -> a + b);
        System.out.println("[P14] big_report 结果在上下文中的长度 = " + bigReportCtx.length()
            + " 字符（占位符样本 = "
            + bigReportCtx.substring(0, Math.min(260, bigReportCtx.length())).replace('\n', '|') + "）");
        // 占位符断言：头尾预览存活 + 全文不在上下文 + 引用路径
        assertTrue(bigReportCtx.contains("saved to") || bigReportCtx.contains("read_file"),
            "占位符应引用卸载路径（saved to/read_file）");
        assertTrue(bigReportCtx.contains(BigReportTools.BIG_HEAD), "占位符应保留头预览标记");
        assertTrue(bigReportCtx.contains(BigReportTools.BIG_TAIL), "占位符应保留尾预览标记");
        assertTrue(bigReportCtx.length() < 85000,
            "上下文不得再含 85K 全文（实测=" + bigReportCtx.length() + " 字符）");
        try (var walk = Files.walk(workspaceDir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    System.out.println("[P14] workspace 文件 = " + workspaceDir.relativize(p) + " (" + Files.size(p) + "B)");
                } catch (Exception ignore) { }
            });
        }

        // 卸载往返：从占位符抽路径 → 按行区间全文读回（真实 read_file 语义；超界起始行=报错，按实测行数取）
        java.util.regex.Matcher pm = java.util.regex.Pattern
            .compile("path `([^`]+)`").matcher(bigReportCtx);
        assertTrue(pm.find(), "占位符应含可解析路径（原文=" + bigReportCtx.substring(0, Math.min(120, bigReportCtx.length())) + "）");
        String evictedFile = pm.group(1);
        System.out.println("[P14] 卸载路径 = " + evictedFile);
        AbstractFilesystem fs = localFs;
        Path diskFile = workspaceDir.resolve(evictedFile);
        assertTrue(Files.exists(diskFile), "卸载文件应真实落盘：" + diskFile);
        // 85K 工具结果无换行=单行文件（实测 1 行 246KB）——read_file 行区间 (0,1) 即全文
        ReadResult full = fs.read(rc, evictedFile, 0, 1);
        assertTrue(full.isSuccess(), "按占位符路径应能读回（error=" + full.error() + "）");
        System.out.println("[P14] read_file 取回长度 = " + full.fileData().content().length());
        // read 对单行长文可能有截断护栏——全文完整性以磁盘原文断言（同一文件，nio 读）
        String fullText = Files.readString(diskFile, StandardCharsets.UTF_8);
        System.out.println("[P14] 卸载全文取回 = " + fullText.length() + " 字符");
        assertTrue(fullText.contains(BigReportTools.BIG_HEAD) && fullText.contains(BigReportTools.BIG_TAIL)
                && fullText.contains(MISSION_ID),
            "卸载文件全文应可取回（头尾标记+missionId 俱全）");

        // 回合2：追问小结果 → 上下文继续增长越过 600 token 压缩阈值 → 压缩应触发
        Msg r2 = agent.call(List.of(user("再调用 small_note 查一下备注，然后复述备注原文。")), rc)
            .block(Duration.ofSeconds(180));
        assertNotNull(r2);
        List<Msg> ctx2 = new ArrayList<>(agent.getAgentState(rc).getContext());
        String summary = agent.getAgentState(rc).getSummary();
        System.out.println("[P14] 回合2后消息数=" + ctx2.size() + "（回合1后=" + ctx1.size()
            + "），state.summary=" + (summary == null ? "null" : summary.substring(0, Math.min(100, summary.length()))));
        ctx2.forEach(m -> System.out.println("[P14] ctx2[" + m.getRole() + "] "
            + textOf(m).substring(0, Math.min(60, textOf(m).length())).replace('\n', '|')));
        boolean compactionFired = (summary != null && !summary.isBlank())
            || ctx2.size() < ctx1.size()
            || ctx2.stream().anyMatch(m -> {
                String t = textOf(m);
                return t.contains("summary of the conversation") || t.contains("SESSION INTENT");
            });
        assertTrue(compactionFired, "越过阈值后压缩应触发（summary 非空或上下文收缩）");
        if (summary != null && !summary.isBlank()) {
            assertTrue(summary.contains(MISSION_ID) || summary.contains("联络") || summary.contains("广播"),
                "摘要应保留业务要点");
        }

        // 回合3（Step4 续跑断言）：压缩后同会话继续问答不串（正常应答且知道红线）
        Msg r3 = agent.call(List.of(user("一句话：成员能发起催办吗？")), rc)
            .block(Duration.ofSeconds(180));
        assertNotNull(r3);
        String t3 = textOf(r3);
        System.out.println("[P14] 压缩后续跑应答 = " + t3.substring(0, Math.min(100, t3.length())));
        // 三审 P2 修正：拒绝词可被模型常识满足——加"成员"身份锚，须答出红线针对的主体
        assertTrue((t3.contains("不能") || t3.contains("不可以") || t3.contains("禁止") || t3.contains("无法"))
                && t3.contains("成员"),
            "压缩后系统提示红线仍生效（应针对成员身份拒绝）");
        } finally {
            // WorkspaceManager 持 SQLite 索引句柄，不 close 卡 @TempDir 清理（任何断言失败也必须关）
            wm.close();
        }
    }

    // ================= Step4（判据修订）：溢出真实行为（1 次被拒调用） =================

    @Test
    void overflowBehaviorExplicitErrorNoHang() throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        Model model = buildModel(cfg);
        // 直接打模型（不经 agent/压缩）：≈200K token 输入远超 128K 窗口
        String huge = filler(400000, "溢出填充");
        List<Msg> msgs = List.of(user("以下是长材料：\n" + huge + "\n请只回复：收到"));
        long t0 = System.currentTimeMillis();
        long took = -1;
        try {
            model.stream(msgs, List.of(), io.agentscope.core.model.GenerateOptions.builder().build())
                .blockLast(Duration.ofSeconds(90));
        } catch (Throwable expected) {
            took = System.currentTimeMillis() - t0;
            String cls = expected.getClass().getSimpleName();
            String msg = String.valueOf(expected.getMessage());
            System.out.println("[P14] 溢出调用 " + took + "ms 显式失败 = " + cls + ": "
                + msg.substring(0, Math.min(160, msg.length())));
            // 三审 P2 修正：异常须归因——连接类/超时类基础设施错误不得冒充"溢出被拒"假绿
            boolean infra = cls.contains("Connect") || cls.contains("Timeout") || cls.contains("timeout")
                || msg.contains("timed out") || msg.contains("Connection");
            assertTrue(!infra, "基础设施错误不得当溢出拒绝（" + cls + ": " + msg.substring(0, 80) + "）");
            // 判据修订核心：错误显式传播（非挂死、非静默截断）——P1 兜底=装配层捕获→二次压缩→重试一次
            assertTrue(took < 90_000, "失败应快速显式（实测 " + took + "ms）");
            return;
        }
        // 没抛=窗口比预期大：记录实际行为（不得假装判据通过）
        took = System.currentTimeMillis() - t0;
        System.out.println("[P14] 意外：400K 字符未被拒（" + took + "ms）——模型窗口比假设大，判据按实际窗口重标");
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "窗口超预期，改按实测窗口重标（登记证据）");
    }

    // ================= 附加①：真实知识包 + 60K 档（1 次大输入调用） =================

    @Test
    void sixtyKTierWithKnowledgePack() throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        String pack = Files.readString(Path.of("document", "evidence", "assistant-p0", "05b-knowledge-pack.md"),
            StandardCharsets.UTF_8);
        // 知识包（≈1.8K tok）+ 填充至 ≈60K token（中文≈0.3 token/字符 → ≈180K 字符）
        String fillerText = filler(178000, "60K档填充");
        String sysPrompt = pack + "\n\n你是 DCS 数据采集系统的工作助手，回答用简洁中文。";
        ReActAgent agent = ReActAgent.builder()
            .sysPrompt(sysPrompt).model(buildModel(cfg)).maxIters(2).build();
        RuntimeContext rc = RuntimeContext.builder()
            .userId("t:978311:p14").sessionId("p14-60k-" + System.currentTimeMillis()).build();

        long t0 = System.currentTimeMillis();
        Msg reply = agent.call(List.of(user(fillerText + "\n\n以上是背景材料。请只回答：材料里『第1条』的标签词是什么？")), rc)
            .block(Duration.ofSeconds(240));
        long took = System.currentTimeMillis() - t0;
        assertNotNull(reply);
        ChatUsage usage = reply.getUsage();
        assertNotNull(usage);
        System.out.println("[P14] 60K 档实测：input=" + usage.getInputTokens()
            + " tok, cached=" + usage.getCachedTokens()
            + ", output=" + usage.getOutputTokens()
            + ", 端到端=" + took + "ms");
        // 档位结论判据：能装下且能答（60K 档=知识包+长材料可行）；cached 观察随证据记录
        assertTrue(usage.getInputTokens() >= 40000, "输入应达 60K 量级（实测 " + usage.getInputTokens()
            + "）——不足则档位判据失真");
        assertTrue(usage.getInputTokens() <= 128000, "须在 128K 窗口内（实测 " + usage.getInputTokens() + "）");
        assertFalseBlank(textOf(reply));
    }

    private static void assertFalseBlank(String s) {
        assertTrue(s != null && !s.isBlank(), "60K 档下模型应正常作答");
    }
}
