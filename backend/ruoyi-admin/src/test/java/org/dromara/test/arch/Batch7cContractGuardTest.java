package org.dromara.test.arch;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 7 · 7C-1 契约守卫（2026-11-20）：读写分家之后，legacy 会话服务不许再长出写路径。
 *
 * <p>为什么要这层：{@code PackageHygieneTest} 只盯文件数与包名，盯不住「同一个类又被塞回新方法」。
 * 7C-1 把 run 台账 16 个方法、消息写 8 个、消息只读 3 个搬走之后，最容易发生的回退是：
 * 下一个人图省事，在 legacy 壳里再加一个 {@code appendXxx} / {@code finishRun}，
 * 于是两套写路径并存——那时门禁全绿，但「哪套是真源」又要靠人记。</p>
 *
 * <p>本类只做三件只减不增的事：① legacy 会话服务的<b>公开方法名白名单</b>（新增即红）；
 * ② controller 里不许出现旧转发壳的调用形态；③ 新根三个服务的公开方法名白名单
 * （防止 7C-2 把清人塞进 RunLedgerService 而不是 PurgeService）。</p>
 *
 * <p>白名单随搬迁同步维护：多一条少一条都会红，这正是目的。</p>
 */
@Tag("local")
class Batch7cContractGuardTest {

    /** 7C-2 后 legacy 会话壳已整个删除；新根三个服务的方法清单是唯一真源 */
    private static final List<String> PURGE_PUBLIC_METHODS = List.of(
        "purgeUser",
        "purgeAgentscopeSessions",
        "retryPurgeAgentscopeSessions",
        "purgeCompensationQueue",
        "dequeuePurgeCompensation",
        "retentionSweep",
        "threadIdsOfUser");

    /** 7C-1 新根台账服务：只放 run/问题行/一轮三表写，不许长消息正文写或清人 */
    private static final List<String> RUN_LEDGER_PUBLIC_METHODS = List.of(
        "ensureConversation",
        "registerRun",
        "persistUserRound",
        "finishRun",
        "runByRunId",
        "touchRun",
        "listStuckRuns",
        "finalizeStuckRun",
        "listRunningOlderThan",
        "finalizeStalledRun",
        "latestRunningRunIdOfThread",
        "recordRunIssue",
        "recordRunIssueForRunningRun",
        "listRunIssues",
        "latestRunIssue");

    /** 7C-1 新根消息写服务：消息表写 + 压缩审计行 + TODO 清单列；不许长 run 台账或清人 */
    private static final List<String> MESSAGE_WRITE_PUBLIC_METHODS = List.of(
        "appendUserMessage",
        "appendAssistantMessage",
        "writeFollowupSuggestions",
        "updatePartialMessage",
        "deletePartialMessage",
        "appendSystemDivider",
        "appendCompactionRecord",
        "saveTodo");

    @Test
    void legacy会话服务必须已被删除() {
        Path legacy = assistantMain()
            .resolve("com/smartdock/assistant/legacy/service/AssistantConversationService.java");
        assertThat(legacy)
            .as("7C-2 已把 legacy 会话壳整个删除（清人→PurgeService、压缩记录/TODO→MessageWriteService、"
                + "账单/TODO 读→ConversationQueryService）。谁再把这个文件加回来，等于重建第二套写路径。")
            .doesNotExist();
    }

    @Test
    void controller不许调用旧转发壳() throws IOException {
        // 7C-1 撤掉了 legacy 壳上的 conversationByThread 转发；谁再写回这形态，等于绕过
        // 新根 query 服务直接借壳——门禁红。
        Pattern legacyShellCall = Pattern.compile(
            "\\b(?:conversationService|AssistantConversationService)\\s*\\.\\s*conversationByThread\\s*\\(");
        List<String> hits = new ArrayList<>();
        for (Path p : javaFiles(assistantMain())) {
            String name = p.getFileName().toString();
            if (!name.endsWith("Controller.java")) {
                continue;
            }
            String src = Files.readString(p, StandardCharsets.UTF_8);
            if (legacyShellCall.matcher(src).find()) {
                hits.add(assistantMain().relativize(p).toString());
            }
        }
        assertThat(hits)
            .as("controller 不许再调 legacy 会话服务的 conversationByThread 转发壳（7C-1 已撤，改走 query.ConversationQueryService）")
            .isEmpty();
    }

    @Test
    void 新根台账服务方法清单不许漂移() throws IOException {
        List<String> actual = publicMethodNames(assistantMain()
            .resolve("com/smartdock/assistant/service/RunLedgerService.java"));
        assertThat(actual)
            .as("RunLedgerService 的公开方法清单必须与 7C 一致（清人归 PurgeService，别塞这里）。当前：%s", actual)
            .containsExactlyInAnyOrderElementsOf(RUN_LEDGER_PUBLIC_METHODS);
    }

    @Test
    void 新根消息写服务方法清单不许漂移() throws IOException {
        List<String> actual = publicMethodNames(assistantMain()
            .resolve("com/smartdock/assistant/service/MessageWriteService.java"));
        assertThat(actual)
            .as("MessageWriteService 的公开方法清单必须与 7C 一致（persistUserRound 归台账，清人归 PurgeService）。当前：%s", actual)
            .containsExactlyInAnyOrderElementsOf(MESSAGE_WRITE_PUBLIC_METHODS);
    }

    @Test
    void 新根清人服务方法清单不许漂移() throws IOException {
        List<String> actual = publicMethodNames(assistantMain()
            .resolve("com/smartdock/assistant/service/PurgeService.java"));
        assertThat(actual)
            .as("PurgeService 的公开方法清单必须与 7C-2 一致（只放清人与保留期；消息/台账别搬回来）。当前：%s", actual)
            .containsExactlyInAnyOrderElementsOf(PURGE_PUBLIC_METHODS);
    }

    // ------------------------------------------------------------------

    /** 抽 {@code public} 方法名，重载只留一份（方法名集合是契约；重载个数变化由编译期与调用点兜住） */
    private static List<String> publicMethodNames(Path file) throws IOException {
        assertThat(file).as("待检文件必须存在：%s", file).exists();
        String src = Files.readString(file, StandardCharsets.UTF_8);
        // 去掉块注释与行注释，避免 javadoc 里的示例被当成方法
        String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        Pattern p = Pattern.compile(
            "(?m)^\\s*public\\s+(?:static\\s+)?(?:final\\s+)?(?:<[^>]+>\\s+)?"
                + "[\\w.$<>\\[\\],\\s?]+?\\s+(\\w+)\\s*\\(");
        List<String> names = new ArrayList<>();
        var m = p.matcher(code);
        while (m.find()) {
            String name = m.group(1);
            // 构造器与 record 组件不算方法
            if (name.equals("AssistantConversationService") || name.equals("RunLedgerService")
                || name.equals("MessageWriteService") || name.equals("PurgeService")
                || name.equals("CompactionBill")) {
                continue;
            }
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static Path assistantMain() {
        Path backend = backendRoot();
        return backend.resolve("ruoyi-modules/ruoyi-assistant/src/main/java");
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).toList();
        }
    }

    private static Path backendRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null) {
            if (Files.isDirectory(p.resolve("ruoyi-modules")) && Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("找不到 backend 根目录");
    }
}
