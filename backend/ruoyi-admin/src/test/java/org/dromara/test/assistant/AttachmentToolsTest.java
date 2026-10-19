package org.dromara.test.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.assistant.domain.DcsAssistantAttachment;
import org.dromara.assistant.service.AssistantAttachmentService;
import org.dromara.assistant.tools.AttachmentTools;
import org.dromara.assistant.tools.ToolIdentity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ATTACH-2 read_attachment 契约单测（2026-10-07；批G G5 改版 2026-10-08）：
 * 两态工具（清单/正文）+ 空态引导 + FAILED 人话 + 12k 字符截断。
 * G5 起出口统一 secureJson（PII+注入打标）——单测 Spring 上下文缺席时
 * secureJson 原样返回（安全件不可用不阻断工具的既定降级），断言直接解析 JSON。
 */
@Tag("dev")
class AttachmentToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AssistantAttachmentService service = mock(AssistantAttachmentService.class);

    private AttachmentTools tools(String threadId) {
        return new AttachmentTools(new ToolIdentity(1L, "000000", ToolIdentity.LEVEL_MEMBER, null),
            threadId, service);
    }

    /** 调工具并解析 JSON 出口（secureJson 无 Spring 上下文=原样直通） */
    private Map<String, Object> call(String threadId, String attachmentId) throws Exception {
        String json = tools(threadId).readAttachment(attachmentId);
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    private static DcsAssistantAttachment row(long id, String status, String text) {
        DcsAssistantAttachment r = new DcsAssistantAttachment();
        r.setId(id);
        r.setFileName("成绩表.xlsx");
        r.setSuffix("xlsx");
        r.setParseStatus(status);
        r.setParsedText(text);
        r.setLineCount(text == null ? 0 : text.split("\n", -1).length);
        return r;
    }

    /** threadId 缺失（旧客户端）→ 人话不可用，不炸 */
    @Test
    void emptyThreadFailsGracefully() throws Exception {
        Map<String, Object> r = call(null, null);
        assertEquals("ATTACHMENT_UNAVAILABLE", r.get("error"));
        assertNotNull(r.get("message"));
    }

    /** 无附件 → 计数 0 + 上传引导话术（二站② AU-49：清单契约=rows/total） */
    @Test
    void emptyThreadListGuidesUpload() throws Exception {
        when(service.listOwned(anyString(), anyLong(), anyString())).thenReturn(List.of());
        Map<String, Object> r = call("t-1", null);
        assertEquals(0, r.get("total"));
        assertTrue(String.valueOf(r.get("message")).contains("docx"), "引导话术含格式清单");
    }

    /** 清单态：rows 包裹+人话状态标签+数字 id；G4 新增 PARSING 归「解析中」；asOf 时间锚(AU-48) */
    @Test
    @SuppressWarnings("unchecked")
    void listModeReturnsHumanLabels() throws Exception {
        when(service.listOwned(anyString(), anyLong(), anyString()))
            .thenReturn(List.of(row(101L, "PARSED", "a\nb"), row(102L, "FAILED", null),
                row(103L, "PARSING", null)));
        Map<String, Object> r = call("t-1", null);
        assertEquals(3, r.get("total"));
        assertNotNull(r.get("asOf"), "二站② AU-48：数据截止时间锚");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("rows");
        assertEquals(3, rows.size());
        assertEquals("101", rows.get(0).get("attachmentId"));
        assertEquals("已解析", rows.get(0).get("statusLabel"));
        assertEquals("解析失败", rows.get(1).get("statusLabel"));
        assertEquals("解析中", rows.get(2).get("statusLabel"), "PARSING(G4 新中间态)归解析中");
    }

    /** 正文态：超长截断并带如实告知（G5 起 PII/注入打标统一走 secureJson 出口） */
    @Test
    void contentModeMasksAndTruncates() throws Exception {
        String big = "行\n".repeat(AttachmentTools.MAX_CHARS / 2 + 10);
        when(service.listOwned(anyString(), anyLong(), anyString()))
            .thenReturn(List.of(row(101L, "PARSED", big)));
        Map<String, Object> r = call("t-1", "101");
        String content = String.valueOf(r.get("content"));
        assertEquals(AttachmentTools.MAX_CHARS, content.length(), "正文截到 12k 字符");
        assertEquals(true, r.get("truncated"));
        assertTrue(String.valueOf(r.get("truncationNote")).contains("如实告知"));
    }

    /** FAILED 附件读正文 → 人话错误不喂文（parse_error 透传） */
    @Test
    void failedAttachmentReturnsFriendlyError() throws Exception {
        DcsAssistantAttachment bad = row(101L, "FAILED", null);
        bad.setParseError("文件较大（超过10MB）");
        when(service.listOwned(anyString(), anyLong(), anyString())).thenReturn(List.of(bad));
        Map<String, Object> r = call("t-1", "101");
        assertEquals("ATTACHMENT_NOT_READY", r.get("error"));
        assertTrue(String.valueOf(r.get("message")).contains("超过10MB"));
        assertFalse(r.containsKey("content"));
    }

    /** 清单外 id（他人会话串号）→ 不返回任何内容 */
    @Test
    void foreignIdRejected() throws Exception {
        when(service.listOwned(anyString(), anyLong(), anyString()))
            .thenReturn(List.of(row(101L, "PARSED", "x")));
        Map<String, Object> r = call("t-1", "999");
        assertEquals("ATTACHMENT_NOT_FOUND", r.get("error"));
        assertFalse(r.containsKey("content"));
    }
}
