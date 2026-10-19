package org.dromara.test.assistant;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.DcsAiConfigMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T5b 探针：教材样包 token 实测 + 必答题判据 + cachedTokens 观察（T5 Step4/7 收口，5 次小额真实调用）。
 * <p>判据（计划 Task 5 Step7 + R-10/GQ-14 拍板）：</p>
 * <ul>
 *   <li>token 体积：整包注入（05b-knowledge-pack.md 全文作系统提示）实测 input token 增量 ≤8000；</li>
 *   <li>必答题（回答质量）：带包回答 7 道必答题，审定答案锚点词命中 ≥8/9（防「删重点凑字数」）；</li>
 *   <li>cachedTokens：同前缀两连调，第二次 cachedTokens&gt;0——DeepSeek 前缀缓存自动命中实证
 *       （知识包固定置于系统提示前缀可获得约 1/10 价格命中，T5 调研结论的运行时复核）。</li>
 * </ul>
 */
@Tag("p0probe")
@SpringBootTest
class P05bKnowledgePackTest {

    private static final long CONFIG_ID = 9100000000003L;
    private static final Path PACK = Path.of("document", "evidence", "assistant-p0", "05b-knowledge-pack.md");
    private static final String BASE_PROMPT = "你是 DCS 数据采集系统的工作助手，回答用简洁中文。";

    @Autowired
    DcsAiConfigMapper aiConfigMapper;

    private ReActAgent buildAgent(DcsAiConfig cfg, String sysPrompt) {
        return ReActAgent.builder()
            .sysPrompt(sysPrompt)
            .model(OpenAIChatModel.builder()
                .baseUrl(cfg.getBaseUrl()).apiKey(cfg.getApiKey()).modelName(cfg.getModelName())
                .stream(false).formatter(new DeepSeekFormatter()).build())
            .maxIters(2)
            .build();
    }

    private record CallResult(String text, int inputTokens, int cachedTokens) {}

    private CallResult call(DcsAiConfig cfg, String sysPrompt, String userMsg, String sessionId) {
        ReActAgent agent = buildAgent(cfg, sysPrompt);
        RuntimeContext rc = RuntimeContext.builder().userId("t:978311:p05b").sessionId(sessionId).build();
        Msg reply = agent.call(List.of(Msg.builderForRole(MsgRole.USER).textContent(userMsg).build()), rc)
            .block(Duration.ofSeconds(120));
        assertNotNull(reply, "模型应答非空");
        String text = String.join("", reply.getContentBlocks(TextBlock.class).stream()
            .map(TextBlock::getText).toList());
        ChatUsage usage = reply.getUsage();
        assertNotNull(usage, "usage 应可读（T5 计费对账前提）");
        return new CallResult(text, usage.getInputTokens(), usage.getCachedTokens());
    }

    /** 体积 + 缓存：三连小调（基线 → 带包 → 带包重复） */
    @Test
    void packTokenSizeAndCacheHit() throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        String pack = Files.readString(PACK, StandardCharsets.UTF_8);
        System.out.println("[P05b] 样包字符数 = " + pack.length());

        CallResult base = call(cfg, BASE_PROMPT, "1+1等于几？只答数字。", "p05b-base-" + System.currentTimeMillis());
        String withPack = pack + "\n\n" + BASE_PROMPT;
        CallResult packed1 = call(cfg, withPack, "1+1等于几？只答数字。", "p05b-pk1-" + System.currentTimeMillis());
        CallResult packed2 = call(cfg, withPack, "1+1等于几？只答数字。", "p05b-pk2-" + System.currentTimeMillis());

        int delta = packed1.inputTokens() - base.inputTokens();
        System.out.println("[P05b] 基线 input=" + base.inputTokens()
            + ", 带包 input=" + packed1.inputTokens()
            + ", 增量=" + delta + " token（判据 ≤8000）");
        System.out.println("[P05b] cachedTokens: 首调=" + packed1.cachedTokens()
            + ", 重复调=" + packed2.cachedTokens() + "（前缀缓存命中观察）");
        assertTrue(delta <= 8000, "样包 token 增量超预算：实际 " + delta);
        assertTrue(delta >= 500, "增量异常小（" + delta + "）——疑似未注入全文");
        assertTrue(packed2.cachedTokens() > 0,
            "同前缀重复调应命中前缀缓存（实测 cached=" + packed2.cachedTokens()
                + "）——未命中则知识包固定前置的成本假设不成立");
    }

    /** 必答题（R-10）：带包回答 7 题，锚点命中 ≥8/9 */
    @Test
    void quizWithPackHitsAnswerAnchors() throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        String pack = Files.readString(PACK, StandardCharsets.UTF_8);
        String withPack = pack + "\n\n" + BASE_PROMPT;
        ReActAgent agent = buildAgent(cfg, withPack);
        RuntimeContext rc = RuntimeContext.builder()
            .userId("t:978311:p05b").sessionId("p05b-quiz-" + System.currentTimeMillis()).build();

        String turn1 = "请逐条编号回答（依据你的业务知识）：\n"
            + "1. 广播任务发布后成员在转发树里看不到自己节点，是系统故障吗？\n"
            + "2. 追加类任务一次勾选多条记录提交后每条会怎样？防重复提交靠什么机制？\n"
            + "3. 成员打开科目任务整页空白，第一个排查方向是什么？\n";
        Msg r1 = agent.call(List.of(Msg.builderForRole(MsgRole.USER).textContent(turn1).build()), rc)
            .block(Duration.ofSeconds(180));
        String turn2 = "继续逐条编号回答：\n"
            + "4. 父任务审核通过后子任务状态变成什么？\n"
            + "5. 什么任务免审直接完成？\n"
            + "6. 2026年8月27日之前的旧数据时间有什么特点？\n"
            + "7. 「我已完成」功能2026年9月10日起有什么变化？\n";
        Msg r2 = agent.call(List.of(Msg.builderForRole(MsgRole.USER).textContent(turn2).build()), rc)
            .block(Duration.ofSeconds(180));
        assertNotNull(r1);
        assertNotNull(r2);
        String answers = String.join("", r1.getContentBlocks(TextBlock.class).stream().map(TextBlock::getText).toList())
            + "\n" + String.join("", r2.getContentBlocks(TextBlock.class).stream().map(TextBlock::getText).toList());
        System.out.println("[P05b] 必答题作答（前600字）= " + answers.substring(0, Math.min(600, answers.length())));

        // 审定答案锚点（R-10：防删重点凑字数——锚点缺失=瘦身删掉了必考内容）。
        // 三审修正：Q6 锚点原为单字符"4"（被"逐条编号回答"的题号必然命中=恒真），改特异锚点
        List<String> anchors = List.of(
            "trace",              // Q1 广播不建 trace
            "独立成批",           // Q2 拆批
            "detailId",           // Q2 幂等排序归一
            "CONTAINER",          // Q3 容器行
            "SUBMITTED",          // Q4 子保持已提交
            "accepted_version",   // Q4 版本投影
            "NONE",               // Q5 免审 reviewerMode
            "4 小时",             // Q6 早4小时（特异锚点——题号"4."不会命中）
            "V2_ACKNOWLEDGE"      // Q7 知晓排除列
        );
        long hits = anchors.stream().filter(answers::contains).count();
        System.out.println("[P05b] 锚点命中 " + hits + "/" + anchors.size()
            + "，未命中=" + anchors.stream().filter(a -> !answers.contains(a)).toList());
        assertTrue(hits >= 8, "必答题锚点命中不足（" + hits + "/" + anchors.size()
            + "）——样包瘦身删掉了必考内容或注入被截断");
    }
}
