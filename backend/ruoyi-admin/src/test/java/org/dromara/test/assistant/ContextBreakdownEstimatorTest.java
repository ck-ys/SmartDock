package org.dromara.test.assistant;

import com.smartdock.assistant.support.ContextBreakdownEstimator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 批 A Q6（2026-11-14）：上下文构成口径纯函数单测。
 * 2026-11-15 按 Q6 框架板 Q3 改为「只数字符」：charsOf 不折算、pct 四舍五入到 0.1。
 */
@Tag("local")
class ContextBreakdownEstimatorTest {

    @Test
    void charsOfNullAndEmptyAreZero() {
        assertEquals(0, ContextBreakdownEstimator.charsOf((String) null));
        assertEquals(0, ContextBreakdownEstimator.charsOf(""));
    }

    @Test
    void charsOfCountsCharactersWithoutConversion() {
        // 不做任何折算：中文 1 字 = 1 字符，英文 1 字母 = 1 字符
        assertEquals(5, ContextBreakdownEstimator.charsOf("中文abc"));
        assertEquals(25, ContextBreakdownEstimator.charsOf("一二三四五六七八九十一二三四五六七八九十一二三四五"));
    }

    @Test
    void charsOfCountsUtf16CodeUnitsForEmoji() {
        // 口径钉死（2026-11-15 三审席3）：UTF-16 码元＝ZCode 的 JS string.length；
        // emoji 记 2、中文/英文记 1。改口径必须同时改面板脚注，不能悄悄换。
        assertEquals(2, ContextBreakdownEstimator.charsOf("😀"));
        assertEquals(23, ContextBreakdownEstimator.charsOf("今天天气不错😀我们出去走走吧哈哈🌈明天再说"));
    }

    @Test
    void charsOfCollectionSumsAndTreatsNullAsZero() {
        assertEquals(0, ContextBreakdownEstimator.charsOf(List.of()));
        assertEquals(0, ContextBreakdownEstimator.charsOf((List<String>) null));
        // null 元素按 0；0+10+5 = 15
        assertEquals(15, ContextBreakdownEstimator.charsOf(Arrays.asList(null, "0123456789", "abcde")));
    }

    @Test
    void pctOfRoundsToOneDecimalAndGuardsZero() {
        assertEquals(0, ContextBreakdownEstimator.pctOf(10, 0));
        assertEquals(0, ContextBreakdownEstimator.pctOf(0, 100));
        // 1/3 = 33.333…% → 33.3
        assertEquals(33.3, ContextBreakdownEstimator.pctOf(1, 3), 1e-9);
        // 7/9 = 77.77% → 77.8
        assertEquals(77.8, ContextBreakdownEstimator.pctOf(7, 9), 1e-9);
        assertEquals(100.0, ContextBreakdownEstimator.pctOf(5, 5), 1e-9);
    }

    @Test
    void methodNameStaysStableForFooter() {
        // 面板脚注 tooltip 直接展示 method 名——改口径必须改测试（防悄悄变算法）
        assertEquals("chars-v1", ContextBreakdownEstimator.METHOD);
    }
}
