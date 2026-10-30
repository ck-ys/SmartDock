package org.dromara.test.assistant;

import org.dromara.assistant.service.AssistantTitleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 题3 标题残片判据（2026-10-30）：权威用例放在 local 组。
 *
 * <p>三审 P1-2 指出的坑：同一批的 {@code TodoAndTitleUnitTest} 是 {@code @Tag("dev")}，
 * 而默认质量门禁跑 {@code -Dsurefire.groups=local}——写库防脏逻辑的用例落在 dev 组等于没跑。</p>
 *
 * <p>纯函数直测，零 Spring / 零模型调用。</p>
 */
@Tag("local")
class AssistantTitleFragmentLocalTest {

    @Test
    void truncatedJsonIsRejected() {
        assertNull(AssistantTitleService.cleanTitle("{\"title\":\"周复盘适"), "截断的 title 值");
        assertNull(AssistantTitleService.cleanTitle("{\"tit"), "只吐出半截键");
        assertNull(AssistantTitleService.cleanTitle("[{\"title\":\"数组残片"), "数组开头的残片");
        assertNull(AssistantTitleService.cleanTitle("{\"title\": \"未闭合引号\"x"), "闭合不全");
        assertNull(AssistantTitleService.cleanTitle("\"未闭合引号开头"), "裸引号未闭合");
    }

    @Test
    void unclosedCodeFenceIsRejectedNotAdopted() {
        // 三审 P2：模型吐了开围栏 + 半截 JSON 时，闭合围栏匹配不上会落到「取首行」，
        // 旧实现会把 ```json 当标题写库并经 CAS 锁死
        assertNull(AssistantTitleService.cleanTitle("```json\n{\"title\":\"周复盘适"));
        assertNull(AssistantTitleService.cleanTitle("```json"), "只有围栏标记");
    }

    @Test
    void normalTitlesSurvive() {
        assertEquals("周复盘适配改造", AssistantTitleService.cleanTitle("周复盘适配改造"));
        assertEquals("去引号", AssistantTitleService.cleanTitle("{\"title\":\"\\\"去引号\\\"\"}"));
        // 闭合围栏 + 完整 JSON 照常取 title
        assertEquals("周复盘", AssistantTitleService.cleanTitle("```json\n{\"title\":\"周复盘\"}\n```"));
    }
}
