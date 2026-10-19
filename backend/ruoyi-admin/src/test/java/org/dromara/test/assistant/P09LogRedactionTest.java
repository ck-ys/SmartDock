package org.dromara.test.assistant;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.DcsAiConfigMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T9 探针：日志脱敏抽查（实测⑨，R-07 补排，1 次真实模型调用）。
 * <p>判据（计划 Task 9）：</p>
 * <ul>
 *   <li>Step1：跑一轮含成员姓名/手机号（PII）+ 唯一标记串的对话 → 扫描测试窗口内新写入的
 *       logs/sys-console.log / sys-error.log / sys-user.log（两处候选目录）：
 *       ①唯一标记串（=用户 prompt 全文/片段）不落日志；②apiKey 明文不落日志；
 *       ③成员姓名+手机号（PII）不落日志；</li>
 *   <li>Step2：命中处记录（框架 logger 位置），处置=日志级别/包装 Logger 方案（登记进证据）。</li>
 * </ul>
 * <p>注：AgentScope 自身 slf4j logger（io.agentscope.*）默认 INFO 级——若 DEBUG 会打请求体，
 * 生产装配须钉死该 logger 级别（结论随证据回填 spec §6）。</p>
 */
@Tag("p0probe")
@SpringBootTest
class P09LogRedactionTest {

    private static final long CONFIG_ID = 9100000000003L;

    @Autowired
    DcsAiConfigMapper aiConfigMapper;

    /** 窗口从类加载起算（覆盖 Spring 启动日志 + 模型调用日志——进程内调用无 HTTP，启动期才是主要写入源） */
    static Instant windowStart;

    @BeforeAll
    static void markWindow() {
        windowStart = Instant.now().minusSeconds(5);
    }

    @Test
    void piiAndApiKeyNeverReachLogs() throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg, "dcs_ai_config 应有 " + CONFIG_ID);
        assertEquals("978311", cfg.getTenantId());
        String apiKey = cfg.getApiKey();
        assertNotNull(apiKey, "apiKey 应已解密为明文（内存中）");

        // 唯一标记 + PII 样本（成员姓名/手机号——脱敏判据的靶子）
        String marker = "P09MARK-" + System.currentTimeMillis();
        String studentName = "欧阳探针";
        String phone = "13900001234";
        String userMsg = "帮我记录：" + marker + "。成员" + studentName + "（手机 " + phone
            + "）问『追加类任务勾选多条提交后每条记录会怎样？』，请用一句话回答。";

        ReActAgent agent = ReActAgent.builder()
            .sysPrompt("你是 DCS 数据采集系统的工作助手，回答用简洁中文。")
            .model(OpenAIChatModel.builder()
                .baseUrl(cfg.getBaseUrl()).apiKey(apiKey).modelName(cfg.getModelName())
                .stream(false).formatter(new DeepSeekFormatter()).build())
            .maxIters(4)
            .build();
        Msg reply = agent.call(List.of(Msg.builderForRole(MsgRole.USER)
                .textContent(userMsg).build()))
            .block(Duration.ofSeconds(120));
        assertTrue(reply != null && !reply.getContentBlocks(TextBlock.class).isEmpty()
                && !reply.getContentBlocks(TextBlock.class).get(0).getText().isBlank(),
            "模型应正常回答（探针前提）");
        Thread.sleep(500);   // 异步 appender 刷盘窗口

        // 扫两处候选日志目录中【测试窗口内新写入】的文件
        List<Path> scanned = new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        for (Path dir : List.of(Path.of("logs"), Path.of("backend", "logs"))) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> fs = Files.list(dir)) {
                for (Path f : fs.filter(p -> p.toString().endsWith(".log")).toList()) {
                    if (Files.getLastModifiedTime(f).toInstant().isBefore(windowStart)) {
                        continue;   // 只扫本测试窗口内动过的文件
                    }
                    scanned.add(f);
                    String content = Files.readString(f, StandardCharsets.UTF_8);
                    if (content.contains(marker)) {
                        hits.add(new Hit(f, "用户 prompt（标记串）落日志"));
                    }
                    if (content.contains(studentName)) {
                        hits.add(new Hit(f, "成员姓名（PII）落日志"));
                    }
                    if (content.contains(phone)) {
                        hits.add(new Hit(f, "手机号（PII）落日志"));
                    }
                    if (content.contains(apiKey)) {
                        hits.add(new Hit(f, "apiKey 明文落日志"));
                    }
                }
            }
        }
        System.out.println("[P09] 扫描文件 = " + scanned);
        System.out.println("[P09] 命中 = " + hits);
        assertFalse(hits.stream().anyMatch(h -> h.what.contains("apiKey")),
            "apiKey 明文不得出现（红线）");
        assertFalse(hits.stream().anyMatch(h -> h.what.contains("PII")),
            "成员姓名/手机号（PII）不得落日志");
        assertFalse(hits.stream().anyMatch(h -> h.what.contains("prompt")),
            "用户 prompt 全文/片段（标记串）不得落日志");
        assertFalse(scanned.isEmpty(), "应至少扫到一个窗口内日志文件（否则判据空转——日志目录排查）");
    }

    record Hit(Path file, String what) {
        @Override public String toString() { return file.getFileName() + ":" + what; }
    }
}
