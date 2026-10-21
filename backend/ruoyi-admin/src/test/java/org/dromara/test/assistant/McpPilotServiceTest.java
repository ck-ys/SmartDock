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

    /** 三审回修后口径：试点一律不接收请求头（黑名单可被 X-Auth-Token/Cookie 绕过→全拒） */
    @Test
    void anyHeadersRejected() {
        for (String bad : new String[]{"{\"Authorization\":\"x\"}", "{\"x-api-key\":\"1\"}", "{\"TOKEN\":\"t\"}", "{\"X-Custom\":\"ok\"}"}) {
            assertThrows(ServiceException.class, () -> org.dromara.assistant.service.McpPilotService.validateHeaders(bad), "试点任何请求头须拒存: " + bad);
        }
        assertDoesNotThrow(() -> org.dromara.assistant.service.McpPilotService.validateHeaders(null));
        assertDoesNotThrow(() -> org.dromara.assistant.service.McpPilotService.validateHeaders(" "));
        assertDoesNotThrow(() -> org.dromara.assistant.service.McpPilotService.validateHeaders("{}"));
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
