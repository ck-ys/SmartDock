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
        // 边界内合法：1024 值上限实测 + 10 键上限实测（三审回修：注释曾声称覆盖未测）
        assertDoesNotThrow(() -> org.dromara.assistant.service.McpPilotService.parseHeaders(
            "{\"X_Api-Key-1\":\"" + "x".repeat(1024) + "\"}"));
        StringBuilder ten = new StringBuilder("{");
        for (int i = 0; i < 10; i++) {
            ten.append("\"H_").append(i).append("\":\"v\",");
        }
        assertEquals(10, org.dromara.assistant.service.McpPilotService.parseHeaders(
            ten.substring(0, ten.length() - 1) + "}").size());
        // G4 三审回修（dsflash P2）：整体 4KB 上限 + 重复键显式拒绝（Jackson 默认后值静默覆盖）
        assertThrows(ServiceException.class, () -> org.dromara.assistant.service.McpPilotService.parseHeaders(
            "{\"a\":\"" + "x".repeat(4200) + "\"}"));
        assertThrows(ServiceException.class, () -> org.dromara.assistant.service.McpPilotService.parseHeaders(
            "{\"a\":\"1\",\"a\":\"2\"}"));
        // validateHeaders 与 parseHeaders 同口径（委托）
        assertDoesNotThrow(() -> org.dromara.assistant.service.McpPilotService.validateHeaders("{\"Authorization\":\"t\"}"));
    }

    /** G4-#3 写类判定纯函数（glm53 终审 P2 抽出后锁行为）：注解显式优先，null 兜底服务级 */
    @Test
    void writeToolDecisionByAnnotationThenServerFlag() {
        assertTrue(org.dromara.assistant.service.McpPilotService.isWriteTool(Boolean.FALSE, true), "注解 FALSE 一律写类（即便服务自称只读）");
        assertTrue(org.dromara.assistant.service.McpPilotService.isWriteTool(Boolean.FALSE, false), "注解 FALSE ×写类服务=写");
        assertFalse(org.dromara.assistant.service.McpPilotService.isWriteTool(Boolean.TRUE, true), "注解 TRUE 放行");
        assertFalse(org.dromara.assistant.service.McpPilotService.isWriteTool(Boolean.TRUE, false), "注解 TRUE 即便写类服务也放行（工具自声明只读）");
        assertFalse(org.dromara.assistant.service.McpPilotService.isWriteTool(null, true), "无注解+只读服务（试点恒 '1'）=只读，行为零变化");
        assertTrue(org.dromara.assistant.service.McpPilotService.isWriteTool(null, false), "无注解+写类服务=fail-safe 视为写（放开 read_only 前置保护）");
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

    /**
     * 题6（2026-10-30 拍板·案A）调用审计落库口径：成功行「详情/错误」不再把耗时抄一遍，
     * 耗时只进 duration_ms；失败行保留真错误。
     *
     * <p>旧口径 error_msg = errorMsg != null ? errorMsg : remark，成功路径 remark 传的是
     * "4ms"——前端两列逐行同值（4ms | 4ms），导出/接口消费方同样拿到错口径。</p>
     */
    @Test
    void auditRowKeepsDurationOutOfErrorMsg() {
        org.dromara.assistant.domain.DcsMcpCallLog ok = org.dromara.assistant.service.McpPilotService.buildCallLog(
            "weather_demo", "000000", 1L, "get_weather", "CALL_OK", "1", null, "4ms");
        assertNull(ok.getErrorMsg(), "成功行不该把耗时写进 error_msg");
        assertEquals(4, ok.getDurationMs());
        assertEquals("CALL_OK", ok.getAction());
        assertEquals("weather_demo", ok.getServerName());

        // 写类成功调用 remark 带 "write " 前缀：耗时仍要解析出来（旧坑：endsWith 解析必抛）
        org.dromara.assistant.domain.DcsMcpCallLog write = org.dromara.assistant.service.McpPilotService.buildCallLog(
            "weather_demo", "000000", 1L, "create_task", "CALL_OK", "1", null, "write 123ms");
        assertNull(write.getErrorMsg());
        assertEquals(123, write.getDurationMs());

        // 失败行：真错误保留，没有耗时尾缀时 durationMs 为空
        org.dromara.assistant.domain.DcsMcpCallLog bad = org.dromara.assistant.service.McpPilotService.buildCallLog(
            "weather_demo", "000000", 1L, "get_weather", "CALL_ERROR", "0",
            "MCP client 'weather_demo' not initialized", null);
        assertEquals("MCP client 'weather_demo' not initialized", bad.getErrorMsg());
        assertNull(bad.getDurationMs());

        // 限频行：既无错误文本也无耗时
        org.dromara.assistant.domain.DcsMcpCallLog limited = org.dromara.assistant.service.McpPilotService.buildCallLog(
            "weather_demo", "000000", 1L, "get_weather", "RATE_LIMITED", "0", null, null);
        assertNull(limited.getErrorMsg());
        assertNull(limited.getDurationMs());
    }

    /**
     * <p>F10（2026-10-31 复审回修）：两处口径漏钉补上——</p>
     * <p>① 成功行×非空 errorMsg 的「强制置空」分支：现有用例成功行 errorMsg 恒传 null，
     * 若实现回归为直接 setErrorMsg(errorMsg)，那些断言照样全绿，这一条专门钉死。</p>
     * <p>② 失败行×带 ms 尾缀 remark：错误保留 + duration 解析；尾缀数字超 int（F9）置 null 且行照常落成。</p>
     */
    @Test
    void buildCallLogClearsErrorMsgOnSuccessEvenIfProvided() {
        org.dromara.assistant.domain.DcsMcpCallLog ok = org.dromara.assistant.service.McpPilotService.buildCallLog(
            "weather_demo", "000000", 1L, "get_weather", "CALL_OK", "1", "不应出现的错误", "4ms");
        assertNull(ok.getErrorMsg(), "成功行即使传了 errorMsg 也必须置空");
        assertEquals(4, ok.getDurationMs());
    }

    @Test
    void buildCallLogParsesFailRemarkMsAndClampsOverflow() {
        // 失败行带耗时尾缀：错误文本保留，duration 照常解析
        org.dromara.assistant.domain.DcsMcpCallLog fail = org.dromara.assistant.service.McpPilotService.buildCallLog(
            "weather_demo", "000000", 1L, "create_task", "CALL_ERROR", "0", "boom", "write 123ms");
        assertEquals("boom", fail.getErrorMsg());
        assertEquals(123, fail.getDurationMs());

        // F9：尾缀数字超 int 上限——置 null，不能抛 NumberFormatException 丢整条审计行
        org.dromara.assistant.domain.DcsMcpCallLog overflow = org.dromara.assistant.service.McpPilotService.buildCallLog(
            "weather_demo", "000000", 1L, "get_weather", "CALL_ERROR", "0", "boom", "99999999999999ms");
        assertEquals("boom", overflow.getErrorMsg());
        assertNull(overflow.getDurationMs(), "超 int 的耗时不该炸行，也不该截断成错值");
    }

    /**
     * 三审 P1（2026-11-07）：自动化是无人值守，只挂显式声明只读的工具。
     * 没注解≠只读——注解是第三方服务自声明，不声明就按写类处理。
     */
    @Test
    void automationMountsOnlyExplicitlyReadOnlyTools() {
        assertTrue(org.dromara.assistant.service.McpPilotService.mountInAutomation(Boolean.TRUE));
        assertFalse(org.dromara.assistant.service.McpPilotService.mountInAutomation(null),
            "无 readOnlyHint 注解的工具不得进自动化白名单");
        assertFalse(org.dromara.assistant.service.McpPilotService.mountInAutomation(Boolean.FALSE));
    }
}
