package org.dromara.test.assistant;

import org.dromara.assistant.core.AssistantAskUserBridge;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题12 案1（2026-10-31）：结构化反问桥的挂起/作答/归属校验契约。
 *
 * <p>纯单测（@Tag("local")）：不起 Spring、不碰数据库。守护三件事：
 * ①ask 在工具线程挂起、answer 完成后返回 answered；②跨租户/跨用户/跨线程一律拒答；
 * ③cancel 收尾为 cancelled、卡载荷字段与前端渲染口径一致。</p>
 */
@Tag("local")
class AssistantAskUserBridgeTest {

    private static final List<Map<String, Object>> QUESTIONS = List.of(
        Map.of("question", "要催哪个任务？", "header", "选任务",
            "options", List.of(Map.of("label", "期末录入"), Map.of("label", "选课审核")),
            "multiSelect", false));

    @Test
    void askBlocksThenAnswerCompletes() throws Exception {
        AssistantAskUserBridge bridge = new AssistantAskUserBridge();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Map<String, Object>> future =
                CompletableFuture.supplyAsync(() -> bridge.ask("t1", 1L, "th1", QUESTIONS), pool);
            List<Map<String, Object>> cards = awaitCards(bridge, "th1");
            assertEquals(1, cards.size());
            String id = String.valueOf(cards.get(0).get("id"));
            assertFalse(id.isBlank());
            assertEquals(QUESTIONS, cards.get(0).get("questions"));
            assertTrue(((Number) cards.get(0).get("remainingMs")).longValue() > 0);

            List<Map<String, Object>> answers = List.of(
                Map.of("header", "选任务", "question", "要催哪个任务？", "picked", List.of("期末录入")));
            assertNull(bridge.answer("t1", 1L, "th1", id, "answer", answers), "归属正确应作答成功");

            Map<String, Object> result = future.get(3, TimeUnit.SECONDS);
            assertEquals("answered", result.get("action"));
            assertEquals(answers, result.get("answers"));
        } finally {
            pool.shutdownNow();
        }
    }

    /** C 七行（2026-11-06）：开了自动继续，到点按「空答案」收尾——不是 timeout，也不替用户乱选。 */
    @Test
    void autoContinueFinishesWithEmptyAnswers() throws Exception {
        // 1 秒到期，autoContinue 档；timeout 档给 30 秒，确保走的是 auto 分支
        AssistantAskUserBridge bridge = new AssistantAskUserBridge(1, 30);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Map<String, Object>> future =
                CompletableFuture.supplyAsync(() -> bridge.ask("t1", 1L, "th-auto", QUESTIONS, true), pool);
            awaitCards(bridge, "th-auto");
            Map<String, Object> result = future.get(4, TimeUnit.SECONDS);
            assertEquals("answered", result.get("action"), "自动继续必须按已作答收尾");
            assertEquals(List.of(), result.get("answers"), "自动继续不带任何选项");
            assertEquals(Boolean.TRUE, result.get("auto"), "必须带 auto 标记，模型据此知道是超时自动走的");
        } finally {
            pool.shutdownNow();
        }
    }

    /** 关掉自动继续：到点按 timeout 收尾（模型改用正文追问），不会替用户继续。 */
    @Test
    void autoContinueOffFinishesWithTimeout() throws Exception {
        AssistantAskUserBridge bridge = new AssistantAskUserBridge(30, 1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Map<String, Object>> future =
                CompletableFuture.supplyAsync(() -> bridge.ask("t1", 1L, "th-off", QUESTIONS, false), pool);
            awaitCards(bridge, "th-off");
            Map<String, Object> result = future.get(4, TimeUnit.SECONDS);
            assertEquals("timeout", result.get("action"));
            assertEquals(List.of(), result.get("answers"));
            assertFalse(result.containsKey("auto"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsCrossTenantUserAndThread() throws Exception {
        AssistantAskUserBridge bridge = new AssistantAskUserBridge();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Map<String, Object>> future =
                CompletableFuture.supplyAsync(() -> bridge.ask("t1", 1L, "th1", QUESTIONS), pool);
            String id = String.valueOf(awaitCards(bridge, "th1").get(0).get("id"));

            assertNotNull(bridge.answer("t2", 1L, "th1", id, "answer", List.of()), "跨租户必须拒");
            assertNotNull(bridge.answer("t1", 2L, "th1", id, "answer", List.of()), "跨用户必须拒");
            assertNotNull(bridge.answer("t1", 1L, "th-other", id, "answer", List.of()), "跨线程必须拒");
            assertFalse(future.isDone(), "被拒的作答不得释放挂起");

            // 正确归属才放行
            assertNull(bridge.answer("t1", 1L, "th1", id, "cancel", null));
            Map<String, Object> result = future.get(3, TimeUnit.SECONDS);
            assertEquals("cancelled", result.get("action"));
            assertEquals(List.of(), result.get("answers"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void answerAfterSettledFailsHonestly() throws Exception {
        AssistantAskUserBridge bridge = new AssistantAskUserBridge();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Map<String, Object>> future =
                CompletableFuture.supplyAsync(() -> bridge.ask("t1", 1L, "th1", QUESTIONS), pool);
            String id = String.valueOf(awaitCards(bridge, "th1").get(0).get("id"));
            assertNull(bridge.answer("t1", 1L, "th1", id, "cancel", null));
            future.get(3, TimeUnit.SECONDS);
            // 已收尾的卡再次作答：给人话「已失效」，不再挂起
            assertTrue(bridge.answer("t1", 1L, "th1", id, "answer", List.of()).contains("已失效"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidActionAndBlankThreadAreSafe() {
        AssistantAskUserBridge bridge = new AssistantAskUserBridge();
        // 无 thread / 无问题直接按未作答收尾，不制造悬挂
        assertEquals("cancelled", bridge.ask("t1", 1L, "", QUESTIONS).get("action"));
        assertEquals("cancelled", bridge.ask("t1", 1L, "th1", List.of()).get("action"));
        // 不存在的 id（含非法 action）一律给人话失效，不抛异常
        assertTrue(bridge.answer("t1", 1L, "th1", "no-such-id", "bogus", List.of()).contains("已失效"));
    }

    @Test
    void cardPayloadIsFrontendShaped() {
        Map<String, Object> payload = AssistantAskUserBridge.cardPayload("id-1", QUESTIONS, -5L);
        assertEquals("id-1", payload.get("id"));
        assertEquals(QUESTIONS, payload.get("questions"));
        assertEquals(0L, payload.get("remainingMs"), "负数剩余时长归零");
    }

    @Test
    void sanitizesAnswersAndRejectsEmptyAnswer() throws Exception {
        AssistantAskUserBridge bridge = new AssistantAskUserBridge();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Map<String, Object>> future =
                CompletableFuture.supplyAsync(() -> bridge.ask("t1", 1L, "th1", QUESTIONS), pool);
            String id = String.valueOf(awaitCards(bridge, "th1").get(0).get("id"));

            // action=answer 但没有任何选择：明确拒绝，不让模型收到「用户已回答：」空串
            assertTrue(bridge.answer("t1", 1L, "th1", id, "answer", List.of()).contains("没有收到选择"));
            assertTrue(bridge.answer("t1", 1L, "th1", id, "answer", null).contains("没有收到选择"));
            // 线程段必填（三段校验不因省略而跳过）
            assertTrue(bridge.answer("t1", 1L, null, id, "answer", List.of()).contains("不属于当前会话"));

            // 超量/超长载荷按卡片同口径收紧：≤4 题、每题 ≤4 项、单项 ≤40 字、补充 ≤200 字
            List<Map<String, Object>> huge = List.of(
                Map.of("header", "H".repeat(50), "question", "Q".repeat(500),
                    "picked", List.of("甲".repeat(80), "乙", "丙", "丁", "戊", "己"),
                    "other", "补".repeat(500)),
                Map.of("picked", List.of()),
                Map.of("picked", List.of("第二题")),
                Map.of("picked", List.of("第三题")),
                Map.of("picked", List.of("第四题")),
                Map.of("picked", List.of("第五题")));
            assertNull(bridge.answer("t1", 1L, "th1", id, "answer", huge));
            Map<String, Object> result = future.get(3, TimeUnit.SECONDS);
            assertEquals("answered", result.get("action"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> saved = (List<Map<String, Object>>) result.get("answers");
            assertEquals(4, saved.size(), "作答条数封顶 4（第 5/6 条丢弃）");
            assertEquals(20, String.valueOf(saved.get(0).get("header")).length(), "header 截 20 字");
            assertEquals(200, String.valueOf(saved.get(0).get("question")).length(), "question 截 200 字");
            @SuppressWarnings("unchecked")
            List<String> picked = (List<String>) saved.get(0).get("picked");
            assertEquals(4, picked.size(), "每題 pick 封顶 4");
            assertEquals(40, picked.get(0).length(), "单项截 40 字");
            assertEquals(200, String.valueOf(saved.get(0).get("other")).length(), "补充截 200 字");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void capsConcurrentCardsPerThread() throws Exception {
        AssistantAskUserBridge bridge = new AssistantAskUserBridge();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CompletableFuture<Map<String, Object>> f1 =
                CompletableFuture.supplyAsync(() -> bridge.ask("t1", 1L, "th1", QUESTIONS), pool);
            CompletableFuture<Map<String, Object>> f2 =
                CompletableFuture.supplyAsync(() -> bridge.ask("t1", 1L, "th1", QUESTIONS), pool);
            List<String> ids = awaitCardIds(bridge, "th1", 2);
            assertEquals(2, ids.size());
            // 同会话第 3 张：直接回绝，不挂死第三个工具线程
            Map<String, Object> third = bridge.ask("t1", 1L, "th1", QUESTIONS);
            assertEquals("busy", third.get("action"));
            // 释放两张，挂起线程收尾
            for (String id : ids) {
                assertNull(bridge.answer("t1", 1L, "th1", id, "cancel", null));
            }
            assertEquals("cancelled", f1.get(3, TimeUnit.SECONDS).get("action"));
            assertEquals("cancelled", f2.get(3, TimeUnit.SECONDS).get("action"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelThreadReleasesOnlyMatchingOwner() throws Exception {
        AssistantAskUserBridge bridge = new AssistantAskUserBridge();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Map<String, Object>> future =
                CompletableFuture.supplyAsync(() -> bridge.ask("t1", 1L, "th1", QUESTIONS), pool);
            awaitCards(bridge, "th1");
            // 归属不符不动它
            bridge.cancelThread("t2", 1L, "th1");
            bridge.cancelThread("t1", 2L, "th1");
            assertFalse(future.isDone());
            bridge.cancelThread("t1", 1L, "th1");
            assertEquals("cancelled", future.get(3, TimeUnit.SECONDS).get("action"));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 轮询取卡：ask 在工具线程挂起后信号才入仓，避免固定 sleep */
    private static List<Map<String, Object>> awaitCards(AssistantAskUserBridge bridge, String threadId) throws Exception {
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> cards = bridge.take(threadId);
            if (cards != null && !cards.isEmpty()) {
                return cards;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等不到反问卡信号");
    }

    /** 收齐 N 张卡的 id（多次 take 累加） */
    private static List<String> awaitCardIds(AssistantAskUserBridge bridge, String threadId, int n) throws Exception {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 200 && ids.size() < n; i++) {
            List<Map<String, Object>> cards = bridge.take(threadId);
            if (cards != null) {
                cards.forEach(c -> ids.add(String.valueOf(c.get("id"))));
            }
            if (ids.size() < n) {
                Thread.sleep(20);
            }
        }
        return ids;
    }
}
