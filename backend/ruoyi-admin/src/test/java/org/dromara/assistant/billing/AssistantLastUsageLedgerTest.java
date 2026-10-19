package org.dromara.assistant.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批B B1 回归锁：消息表 total_tokens 的数据源不变量——「末次模型调用，last-wins，禁累计求和」。
 *
 * <p>口径来源（拍板 Q2-A + cline 公式 + glm53 P1-1 回修）：带工具的一轮回答有多次模型调用，
 * 每次都重发全部历史；上下文占用 = 最后一次调用的 usage.totalTokens()=input+output
 * （OpenAI 口径 prompt_tokens 已含 cached，禁再叠加）。若按累计求和会虚高一倍以上，
 * 叠加 cached 会虚高近两倍——前端环都会把用户吓进不必要的新会话。</p>
 */
@Tag("dev")
@DisplayName("批B B1 末次调用 tokens 快照（last-wins 禁求和禁叠加 cached）")
class AssistantLastUsageLedgerTest {

    @Test
    void lastCallWinsNotSum() {
        AssistantCostLedger ledger = new AssistantCostLedger();
        // ReAct 三子调用 totalTokens：1200 → 5300 → 9400（各自=input+output）
        ledger.recordLastUsage("run-1", 1200L);
        ledger.recordLastUsage("run-1", 5300L);
        ledger.recordLastUsage("run-1", 9400L);
        // 末次 = 9400；累计求和错值 = 15900（正是要防的虚高）
        assertEquals(9400L, ledger.lastUsageOf("run-1"));
    }

    @Test
    void peekDoesNotClearUntilFinish() {
        AssistantCostLedger ledger = new AssistantCostLedger();
        ledger.recordLastUsage("run-2", 3500L);
        // TEXT_MESSAGE_END 异步落库与终态清除有时序差——peek 不清，重复读同值
        assertEquals(3500L, ledger.lastUsageOf("run-2"));
        assertEquals(3500L, ledger.lastUsageOf("run-2"));
        ledger.clearLastUsage("run-2");
        assertNull(ledger.lastUsageOf("run-2"));
    }

    @Test
    void blankRunIdIsNoOp() {
        AssistantCostLedger ledger = new AssistantCostLedger();
        ledger.recordLastUsage(null, 150L);
        ledger.recordLastUsage(" ", 150L);
        assertNull(ledger.lastUsageOf(null));
        assertNull(ledger.lastUsageOf(""));
        ledger.clearLastUsage(null); // 不得抛
    }

    @Test
    void runsAreIsolatedByRunId() {
        AssistantCostLedger ledger = new AssistantCostLedger();
        ledger.recordLastUsage("run-a", 1100L);
        ledger.recordLastUsage("run-b", 2200L);
        ledger.clearLastUsage("run-a");
        assertEquals(2200L, ledger.lastUsageOf("run-b"));
        assertNull(ledger.lastUsageOf("run-a"));
    }

    @Test
    void awaitChangedFromEmptyPicksUpLateRecord() throws Exception {
        // 单调用轮（before=null）：record() 在 TEXT_MESSAGE_END 后 ~1ms 才落 map
        // （E2E 雪花 ID 同毫秒序列+1 实证）——异步落库线程必须兜住该时序差
        AssistantCostLedger ledger = new AssistantCostLedger();
        Thread late = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                // 测试线程不中断
            }
            ledger.recordLastUsage("run-late", 9680L);
        });
        late.start();
        Long v = ledger.awaitLastUsageChanged("run-late", null, 2000L);
        late.join();
        assertEquals(9680L, v);
    }

    @Test
    void awaitChangedWaitsPastStaleValue() throws Exception {
        // glm53 P1-2 回归锁：多子调用轮 TEXT_MESSAGE_END 时 map 里躺的是倒数第二次调用的
        // 旧值——「非 null 即用」会把上一轮 context 当本轮值；必须等到值变化
        AssistantCostLedger ledger = new AssistantCostLedger();
        ledger.recordLastUsage("run-multi", 559L); // 倒数第二次调用（旧值）
        Thread late = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                // 测试线程不中断
            }
            ledger.recordLastUsage("run-multi", 9513L); // 终答那次调用
        });
        late.start();
        Long v = ledger.awaitLastUsageChanged("run-multi", 559L, 2000L);
        late.join();
        assertEquals(9513L, v);
    }

    @Test
    void awaitChangedTimesOutReturningCurrentBestEffort() {
        // 等不到变化（极端滞后）：返回当前值 best-effort（与旧快路径同级降级），有界不无限阻塞
        AssistantCostLedger ledger = new AssistantCostLedger();
        ledger.recordLastUsage("run-stale", 559L);
        long t0 = System.currentTimeMillis();
        Long v = ledger.awaitLastUsageChanged("run-stale", 559L, 400L);
        long elapsed = System.currentTimeMillis() - t0;
        assertEquals(559L, v);
        assertTrue(elapsed >= 400L, "await 应等到超时窗");
        assertTrue(elapsed < 2000L, "await 不得无限阻塞");
        // before=null 且一直无值：超时返回 null
        assertNull(ledger.awaitLastUsageChanged("run-none", null, 300L));
    }

    @Test
    void conditionalClearOnlyRemovesMatchingValue() {
        // glm53 P2-1 回归锁：无条件 remove 会抹掉下一文本消息刚写入的新值——值相等才删
        AssistantCostLedger ledger = new AssistantCostLedger();
        ledger.recordLastUsage("run-cc", 100L);
        ledger.clearLastUsageIf("run-cc", 200L); // 不匹配：不删
        assertEquals(100L, ledger.lastUsageOf("run-cc"));
        ledger.clearLastUsageIf("run-cc", 100L); // 匹配：删
        assertNull(ledger.lastUsageOf("run-cc"));
        ledger.recordLastUsage("run-cc2", 300L);
        ledger.clearLastUsageIf("run-cc2", null); // expected=null（超时未取到）：不删，靠环形截断
        assertEquals(300L, ledger.lastUsageOf("run-cc2"));
    }

    @Test
    void ringBufferEvictsOldestBeyondCap() {
        // AU-45 同款环形截断：超 512 条按插入序淘汰最旧，防中断 run 残留泄漏
        AssistantCostLedger ledger = new AssistantCostLedger();
        for (long i = 0; i < 600; i++) {
            ledger.recordLastUsage("run-" + i, i);
        }
        assertNull(ledger.lastUsageOf("run-0"), "最旧条目应被淘汰");
        assertNull(ledger.lastUsageOf("run-87"), "超限条目应被淘汰");
        assertEquals(599L, ledger.lastUsageOf("run-599"), "最新条目必须在");
    }
}
