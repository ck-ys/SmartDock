package org.dromara.test.assistant;

import org.dromara.assistant.service.AssistantMemoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期记忆纯函数单测（ckys3 解锁板题7~题10 案1，2026-11-12）。
 *
 * <p>覆盖：类型推断（题7）、同主题键（题8）、相关性打分（题9）、字数预算拼装（题9）、
 * 归档截止时刻（题10）。全是静态纯函数，不连库不连 Spring（@Tag("local")）。</p>
 */
@Tag("local")
@DisplayName("长期记忆：类型/主题/打分/预算/归档")
class AssistantMemoryLogicTest {

    @Test
    @DisplayName("类型推断：称呼类=user，人名/团队=team，行程会议=project，其余=reference")
    void detectType() {
        assertEquals("user", AssistantMemoryService.detectType("以后叫我老周"));
        assertEquals("user", AssistantMemoryService.detectType("回答请用中文，语气随意点"));
        assertEquals("team", AssistantMemoryService.detectType("周例会由王工负责主持"));
        assertEquals("project", AssistantMemoryService.detectType("下周要去杭州出差"));
        assertEquals("project", AssistantMemoryService.detectType("版本 2.1 下周三上线"));
        assertEquals("reference", AssistantMemoryService.detectType("公司食堂中午 12 点开饭"));
        assertEquals("reference", AssistantMemoryService.detectType(""));
        assertEquals("reference", AssistantMemoryService.detectType(null));
    }

    @Test
    @DisplayName("同主题键：只认参数型偏好；事实类内容返回 null（不自动覆盖）")
    void topicKey() {
        assertEquals("call:name", AssistantMemoryService.topicKeyOf("以后叫我老周"));
        assertEquals("call:name", AssistantMemoryService.topicKeyOf("称呼我为小林"));
        // 模型改写口径（2026-11-12 活体抓到）：实际落库的是「称呼用户为老王」，只认「称呼我/称呼为」会漏
        assertEquals("call:name", AssistantMemoryService.topicKeyOf("称呼用户为老王"));
        assertEquals("reply:lang", AssistantMemoryService.topicKeyOf("以后都用中文回答"));
        assertEquals("remind:default", AssistantMemoryService.topicKeyOf("催办默认提醒今天截止"));
        assertEquals("time:zone", AssistantMemoryService.topicKeyOf("我的工作时间是早九晚六"));
        // 事实类：不覆盖（只做精确去重）
        assertNull(AssistantMemoryService.topicKeyOf("下周要去杭州出差"));
        assertNull(AssistantMemoryService.topicKeyOf("周例会周五 3 点，王工负责"));
        assertNull(AssistantMemoryService.topicKeyOf(null));
    }

    @Test
    @DisplayName("相关性：问周会→命中年会记忆；问无关话题→0 分（不注入）")
    void scoreRelevance() {
        String mem = "周例会周五 3 点，王工负责";
        assertTrue(AssistantMemoryService.scoreOf(mem, "这周五的周会是几点？") > 0);
        assertTrue(AssistantMemoryService.scoreOf(mem, "周会谁负责") > 0);
        assertEquals(0, AssistantMemoryService.scoreOf(mem, "帮我查一下上个月的销售额"));
        assertEquals(0, AssistantMemoryService.scoreOf(mem, ""));
        assertEquals(0, AssistantMemoryService.scoreOf("", "周会几点"));
    }

    @Test
    @DisplayName("相关性：英文词额外加权——版本号这类专有词命中分更高")
    void scoreEnglishWord() {
        int withWord = AssistantMemoryService.scoreOf("PostgreSQL 17 升级排在周四", "PostgreSQL 什么时候升级");
        int withoutWord = AssistantMemoryService.scoreOf("数据库升级排在周四", "PostgreSQL 什么时候升级");
        assertTrue(withWord > withoutWord, "withWord=" + withWord + " withoutWord=" + withoutWord);
    }

    @Test
    @DisplayName("预算拼装：超预算截断；第一条无论多长都保留；空列表返回 null")
    void joinBudget() {
        List<String> two = Arrays.asList("以后叫我老周", "下周去杭州出差");
        AssistantMemoryService.DigestResult ok =
            AssistantMemoryService.joinWithBudget(two, Arrays.asList(1L, 2L), 800);
        assertNotNull(ok);
        assertEquals(2, ok.count());
        assertTrue(ok.text().startsWith("「以后叫我老周」"));

        // 预算只够第一条：第二条被截断（第一条 6 字 + 3 余量 ≤ 12；两条 18 > 12）
        AssistantMemoryService.DigestResult tight =
            AssistantMemoryService.joinWithBudget(two, Arrays.asList(1L, 2L), 12);
        assertNotNull(tight);
        assertEquals(1, tight.count());
        assertEquals(1, tight.ids().size());

        // 超长第一条：仍保留（至少注入一条，不会因为预算空转）
        String longOne = "记".repeat(300);
        AssistantMemoryService.DigestResult first =
            AssistantMemoryService.joinWithBudget(List.of(longOne), List.of(9L), 50);
        assertNotNull(first);
        assertEquals(1, first.count());

        assertNull(AssistantMemoryService.joinWithBudget(List.of(), List.of(), 800));
        assertNull(AssistantMemoryService.joinWithBudget(null, null, 800));
    }

    @Test
    @DisplayName("归档截止：now-90 天；边界正好 90 天前算到期")
    void archiveCutoff() {
        Date now = new Date(1_700_000_000_000L);
        Date before = AssistantMemoryService.archiveBefore(now, 90);
        long days = (now.getTime() - before.getTime()) / (24L * 3600 * 1000);
        assertEquals(90, days);
        assertTrue(before.before(now));
    }

    @Test
    @DisplayName("常量口径：单条 200 / 每人 50 / 注入预算 800 / 归档 90 天")
    void constants() throws Exception {
        assertEquals(200, readStatic("MAX_CONTENT"));
        assertEquals(50, readStatic("MAX_PER_USER"));
        assertEquals(800, readStatic("MAX_DIGEST_CHARS"));
        assertEquals(90, readStatic("ARCHIVE_DAYS"));
    }

    @Test
    @DisplayName("回合账本按 threadId 会合：取走即清、跨会话不串、count=0 清残留")
    void turnLedger() {
        org.dromara.assistant.core.AssistantMemoryTurnLedger ledger =
            new org.dromara.assistant.core.AssistantMemoryTurnLedger();
        // 注入统计 + 覆盖事件同会话会合
        ledger.recordInjected("th-a", 2, 46);
        ledger.recordSuperseded("th-a", "以后叫我老周", "以后叫我老王");
        org.dromara.assistant.core.AssistantMemoryTurnLedger.TurnMemory mem = ledger.take("th-a");
        assertNotNull(mem);
        assertEquals(2, mem.injected().count());
        assertEquals(46, mem.injected().chars());
        assertEquals(1, mem.superseded().size());
        assertEquals("以后叫我老周", mem.superseded().get(0).from());
        assertNull(ledger.take("th-a"), "取走即清，不会二次发帧");
        // 跨会话隔离（另一个 thread 的记录不被读走）
        ledger.recordInjected("th-b", 1, 20);
        assertNull(ledger.take("th-c"), "无记录的会话不发帧");
        assertNotNull(ledger.take("th-b"), "各自会话独立");
        // 本轮没命中（count=0）=显式清空上一轮残留
        ledger.recordInjected("th-d", 3, 99);
        ledger.recordInjected("th-d", 0, 0);
        assertNull(ledger.take("th-d"), "清零后不发「参考记忆」行");
    }

    private static int readStatic(String name) throws Exception {
        java.lang.reflect.Field f = AssistantMemoryService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }
}
