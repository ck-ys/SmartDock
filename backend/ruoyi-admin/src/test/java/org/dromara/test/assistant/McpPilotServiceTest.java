package org.dromara.test.assistant;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批2 Q2=A（MCP 试点）纯函数单测：白名单校验三件套（危险头/args 解析/csv）+ 截断。
 * STDIO 目录白名单与 HTTP host 白名单依赖文件系统/URI，走 E2E 实测（本页 §B 验收三步）。
 */
@Tag("local")
class McpPilotServiceTest {

    /**
     * G4-#1（2026-10-22）新口径：请求头放开接收（值 @EncryptField AES 落库 + 回显只出键名 +
     * save 端点不落请求体日志），鉴权头（Authorization/X-Api-Key）正是目标——黑名单不再存在；
     * 校验收窄为形状与尺寸。旧「试点一律拒收」断言随口径作废。
     */
    @Test
    void headersShapeAndSizeValidated() {
        // 鉴权头合法接收（这是本功能的目标场景）
        assertEquals(java.util.Map.of("Authorization", "Bearer abc"),
            org.dromara.assistant.service.McpPilotService.parseHeaders("{\"Authorization\":\"Bearer abc\"}"));
        // 空/空白/空对象=空 map
        assertTrue(org.dromara.assistant.service.McpPilotService.parseHeaders(null).isEmpty());
        assertTrue(org.dromara.assistant.service.McpPilotService.parseHeaders(" ").isEmpty());
        assertTrue(org.dromara.assistant.service.McpPilotService.parseHeaders("{}").isEmpty());
        // 畸形：非 JSON / 非对象值 / 非法键名字符 / 超键数 / 值非字符串 / 值过长
        assertThrows(ServiceException.class, () -> org.dromara.assistant.service.McpPilotService.parseHeaders("{bad"));
        assertThrows(ServiceException.class, () -> org.dromara.assistant.service.McpPilotService.parseHeaders("{\"a b\":\"1\"}"));
        assertThrows(ServiceException.class, () -> org.dromara.assistant.service.McpPilotService.parseHeaders("{\"a\":1}"));
        assertThrows(ServiceException.class, () -> org.dromara.assistant.service.McpPilotService.parseHeaders("{\"a\":\"\"}"));
        StringBuilder many = new StringBuilder("{");
        for (int i = 0; i < 11; i++) {
            many.append("\"h").append(i).append("\":\"v\",");
        }
        assertThrows(ServiceException.class, () -> org.dromara.assistant.service.McpPilotService.parseHeaders(
            many.substring(0, many.length() - 1) + "}"));
        assertThrows(ServiceException.class, () -> org.dromara.assistant.service.McpPilotService.parseHeaders(
            "{\"a\":\"" + "x".repeat(1025) + "\"}"));
        // 边界内合法：10 键、1024 值、字母数字-_ 键名
        assertDoesNotThrow(() -> org.dromara.assistant.service.McpPilotService.parseHeaders(
            "{\"X_Api-Key-1\":\"" + "x".repeat(1024) + "\"}"));
        // validateHeaders 与 parseHeaders 同口径（委托）
        assertDoesNotThrow(() -> org.dromara.assistant.service.McpPilotService.validateHeaders("{\"Authorization\":\"t\"}"));
    }

    @Test
    void argsMustBeStringArray() {
        assertEquals(java.util.List.of("a.mjs"), org.dromara.assistant.service.McpPilotService.parseArgs("[\"a.mjs\"]"));
        assertEquals(java.util.List.of(), org.dromara.assistant.service.McpPilotService.parseArgs(null));
        assertThrows(ServiceException.class, () -> org.dromara.assistant.service.McpPilotService.parseArgs("{\"a\":1}"));
    }

    @Test
    void csvSplitAndTruncate() {
        assertEquals(java.util.List.of("a", "b"), org.dromara.assistant.service.McpPilotService.splitCsv(" a , ,b,"));
        assertEquals(java.util.List.of(), org.dromara.assistant.service.McpPilotService.splitCsv(null));
        assertEquals(480, org.dromara.assistant.service.McpPilotService.truncate("x".repeat(999)).length());
        assertEquals("ok", org.dromara.assistant.service.McpPilotService.truncate("ok"));
        assertEquals("", org.dromara.assistant.service.McpPilotService.truncate(null));
    }
}
