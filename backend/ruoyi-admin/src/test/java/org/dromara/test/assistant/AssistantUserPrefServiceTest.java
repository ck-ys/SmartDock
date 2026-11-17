package org.dromara.test.assistant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import com.smartdock.assistant.domain.bo.AssistantUserPrefBo;
import com.smartdock.assistant.service.AssistantUserPrefService;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批C Q8（2026-11-15）：常规偏好纯函数口径。
 *
 * <p>覆盖三件事：①缺值落默认（与前端 DEFAULTS 对齐）；②'1'/'0' 与布尔的互转；
 * ③枚举外的值必须拒绝——静默改默认会让用户以为保存成功了。</p>
 */
@Tag("local")
class AssistantUserPrefServiceTest {

    @Test
    void bitDefaultsWhenNull() {
        assertEquals("1", AssistantUserPrefService.bit(null, true));
        assertEquals("0", AssistantUserPrefService.bit(null, false));
        assertEquals("1", AssistantUserPrefService.bit(Boolean.TRUE, false));
        assertEquals("0", AssistantUserPrefService.bit(Boolean.FALSE, true));
    }

    @Test
    void flagIsTrueUnlessExplicitZero() {
        // 缺行/老数据可能不带值：只有明确的 '0' 才算关
        assertTrue(AssistantUserPrefService.flag(null));
        assertTrue(AssistantUserPrefService.flag("1"));
        assertTrue(!AssistantUserPrefService.flag("0"));
    }

    @Test
    void pickKeepsAllowedValue() {
        Set<String> allowed = Set.of("enter", "ctrl-enter");
        assertEquals("ctrl-enter", AssistantUserPrefService.pick("ctrl-enter", "enter", allowed, "发送键"));
    }

    @Test
    void pickFallsBackWhenBlank() {
        Set<String> allowed = Set.of("system", "full", "reduced");
        assertEquals("system", AssistantUserPrefService.pick(null, "system", allowed, "动效强度"));
        assertEquals("system", AssistantUserPrefService.pick("", "system", allowed, "动效强度"));
    }

    @Test
    void pickRejectsUnknownValue() {
        Set<String> allowed = Set.of("standard", "large");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> AssistantUserPrefService.pick("huge", "standard", allowed, "回答字号"));
        assertTrue(ex.getMessage().contains("回答字号"));
        assertTrue(ex.getMessage().contains("huge"));
    }

    @Test
    void boNullMeansAllDefaults() {
        // 空对象 {}（字段全 null）= 全默认。注意：真正无 body 的请求会在 @RequestBody 解析阶段被拒，
        // 「空 body = 全默认」不成立；这里覆盖的是空对象语义。
        AssistantUserPrefBo bo = new AssistantUserPrefBo();
        assertEquals("enter", AssistantUserPrefService.pick(bo.getSendKey(), AssistantUserPrefService.DEF_SEND_KEY,
            Set.of("enter", "ctrl-enter"), "发送键"));
        assertEquals("1", AssistantUserPrefService.bit(bo.getStreamOutput(), AssistantUserPrefService.DEF_STREAM_OUTPUT));
        assertEquals("0", AssistantUserPrefService.bit(bo.getToolsOpen(), AssistantUserPrefService.DEF_TOOLS_OPEN));
    }

    @Test
    void pickTruncatesLongEcho() {
        // 非法值可能是一整段长文本：回显前截断，别把原文塞进响应和操作日志
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> AssistantUserPrefService.pick("x".repeat(80), "standard", Set.of("standard", "large"), "回答字号"));
        assertTrue(ex.getMessage().contains("…"));
        assertTrue(ex.getMessage().length() < 80);
    }
}
