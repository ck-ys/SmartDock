package org.dromara.assistant.automation.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动化文案纯函数单测（2026-11-06 视觉验收补）。
 *
 * <p>覆盖：markdown 记号抹除（通知正文与运行摘要是纯文本位）、按码点截断不劈 emoji。
 * 无 Spring 依赖，{@code -P local} 直跑。</p>
 */
@Tag("local")
class AutomationNotifierTest {

    @Test
    void plainStripsBoldAndInlineCode() {
        assertThat(AutomationNotifier.plain("**要点汇报** 共 `3` 条")).isEqualTo("要点汇报 共 3 条");
    }

    @Test
    void plainFlattensMarkdownLinks() {
        assertThat(AutomationNotifier.plain("见 [办公用品领用登记](/dcs/mission/detail/1)"))
            .isEqualTo("见 办公用品领用登记");
        assertThat(AutomationNotifier.plain("![图](/a.png)")).isEqualTo("");
    }

    @Test
    void plainDropsHeadingAndListMarkers() {
        assertThat(AutomationNotifier.plain("# 今日汇总\n- 第一条\n* 第二条\n+ 第三条"))
            .isEqualTo("今日汇总\n第一条\n第二条\n第三条");
    }

    @Test
    void excerptFlattensWhitespaceAndKeepsBoldText() {
        assertThat(AutomationNotifier.excerpt("**要点**\n\n  共 **3** 条  ")).isEqualTo("要点 共 3 条");
    }

    @Test
    void truncateDoesNotSplitEmoji() {
        // 「😀」是代理对：按 String.length 截会劈成半个非法字符
        String out = AutomationNotifier.truncate("😀😀😀", 2);
        assertThat(out).isEqualTo("😀😀…");
        assertThat(out.codePointCount(0, out.length())).isEqualTo(3);
    }

    @Test
    void truncateKeepsShortTextUntouched() {
        assertThat(AutomationNotifier.truncate("短句", 10)).isEqualTo("短句");
        assertThat(AutomationNotifier.truncate(null, 10)).isEqualTo("");
    }
}
