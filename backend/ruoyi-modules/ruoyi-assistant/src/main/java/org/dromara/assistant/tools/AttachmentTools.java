package org.dromara.assistant.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantAttachment;
import org.dromara.assistant.service.AssistantAttachmentService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话附件工具（附件批 ATTACH-2，2026-10-07）。
 *
 * <p>两态单工具：不带 attachment_id=列本会话附件清单（人话状态）；带 id=读该附件
 * 正文（~12k 字符截断，超长如实标注）。threadId 随构造器进
 * （工厂从身份桥取，每 (user,thread) 各建 agent——工具实例与会话绑定）。</p>
 *
 * <p>口径：正文即 dcs_assistant_attachment.parsed_text（登记时已截 8000 行），
 * 注入模型再截 {@link #MAX_CHARS}——钱包预算内；FAILED 附件只报人话错误不喂文。
 * G5(批G 1008)：出口改走 {@link ToolSupport#secureJson}——文件正文是用户可控的
 * 最大注入载体，与其它工具同款 PII 掩码+注入确定性打标（本地掩码移交统一出口，
 * 不再双份）。</p>
 */
@Slf4j
public class AttachmentTools {

    /** 模型注入字符上限（~12k 字符，计划 ATTACH 约束；DB 侧另有 8000 行护栏） */
    public static final int MAX_CHARS = 12_000;

    private final ToolIdentity identity;
    private final String threadId;
    private final AssistantAttachmentService attachmentService;

    public AttachmentTools(ToolIdentity identity, String threadId,
                           AssistantAttachmentService attachmentService) {
        this.identity = identity;
        this.threadId = threadId == null ? "" : threadId.trim();
        this.attachmentService = attachmentService;
    }

    @Tool(name = "read_attachment",
        description = "读取用户在当前会话上传的附件：不带 attachment_id 返回附件清单"
            + "（rows 列表：文件名/格式/状态/行数/attachment_id）；带 attachment_id 返回该文件"
            + "解析出的文本内容。用户问到附件、上传的文件、表格内容时必须先调用本工具。",
        readOnly = true)
    public String readAttachment(
        @ToolParam(name = "attachment_id", required = false,
            description = "要读正文的附件 id（从清单获取）；省略=返回清单") String attachmentId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ToolContracts.KEY_AS_OF, ToolSupport.nowIso());
        Map<String, Object> as = new LinkedHashMap<>();
        as.put("tenantId", identity.getTenantId());
        as.put("userId", identity.getUserId());
        result.put(ToolContracts.KEY_QUERIED_AS, as);
        if (threadId.isEmpty()) {
            result.put("error", "ATTACHMENT_UNAVAILABLE");
            result.put("message", "当前会话标识缺失，附件功能暂不可用");
            return ToolSupport.secureJson(result, identity);
        }
        List<DcsAssistantAttachment> rows;
        try {
            // 二站② AU-49：超时包裹与其它工具同口径（DB 挂死不再拖死会话）
            rows = ToolSupport.withTimeout(ToolContracts.DEFAULT_TIMEOUT_MS, () ->
                attachmentService.listOwned(identity.getTenantId(), identity.getUserId(), threadId));
        } catch (Exception e) {
            result.put(ToolContracts.KEY_ERROR, "TIMEOUT");
            result.put("errorType", "TIMEOUT");
            result.put("message", "附件读取超时，建议重试一次");
            return ToolSupport.secureJson(result, identity);
        }
        if (rows.isEmpty()) {
            result.put("message", "本会话还没有附件。请引导用户在输入框左侧「添加」面板上传"
                + " docx/xlsx/pptx/pdf/txt（单文件≤10MB，每会话最多 3 个）后再问。");
            result.put(ToolContracts.KEY_TOTAL, 0);
            return ToolSupport.secureJson(result, identity);
        }
        boolean wantContent = attachmentId != null && !attachmentId.isBlank();
        if (!wantContent) {
            // 二站② AU-49：清单改 rows 包裹（与其它查询工具契约统一，此前扁平键 attachmentN）
            List<Map<String, Object>> items = new java.util.ArrayList<>(rows.size());
            for (DcsAssistantAttachment row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("attachmentId", String.valueOf(row.getId()));
                item.put("fileName", row.getFileName());
                item.put("format", row.getSuffix());
                item.put("statusLabel", statusLabel(row));
                item.put("lineCount", row.getLineCount());
                items.add(item);
            }
            result.put(ToolContracts.KEY_TOTAL, items.size());
            result.put(ToolContracts.KEY_ROWS, items);
            result.put(ToolContracts.KEY_TRUNCATED, false);
            result.put("hint", "需要读某个文件内容时带 attachment_id 再调一次");
            return ToolSupport.secureJson(result, identity);
        }
        // 读正文：id 匹配（防串行——清单外 id 直接人话拒绝）
        Long wanted;
        try {
            wanted = Long.parseLong(attachmentId.trim());
        } catch (NumberFormatException e) {
            result.put("error", "BAD_ATTACHMENT_ID");
            result.put("message", "attachment_id 应为清单里的数字 id");
            return ToolSupport.secureJson(result, identity);
        }
        DcsAssistantAttachment target = rows.stream()
            .filter(r -> wanted.equals(r.getId())).findFirst().orElse(null);
        if (target == null) {
            result.put("error", "ATTACHMENT_NOT_FOUND");
            result.put("message", "该附件不在当前会话（每会话最多 3 个，以清单为准）");
            return ToolSupport.secureJson(result, identity);
        }
        result.put("fileName", target.getFileName());
        result.put("format", target.getSuffix());
        String status = target.getParseStatus() == null ? "" : target.getParseStatus();
        if (!"PARSED".equals(status) && !"TRUNCATED".equals(status)) {
            result.put("error", "ATTACHMENT_NOT_READY");
            result.put("statusLabel", statusLabel(target));
            result.put("message", "FAILED".equals(status)
                ? "该文件解析失败：" + safeError(target)
                : "该文件尚未解析完成，请稍后再试");
            return ToolSupport.secureJson(result, identity);
        }
        String text = target.getParsedText() == null ? "" : target.getParsedText();
        boolean charTruncated = false;
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS);
            charTruncated = true;
        }
        // PII 掩码+注入打标统一走 secureJson 出口（G5 批G 1008）
        result.put("content", text);
        result.put("lineCount", target.getLineCount());
        if (charTruncated || "TRUNCATED".equals(status)) {
            result.put("truncated", true);
            result.put("truncationNote", "文件过长已截取（内容不完整），"
                + "回答时须如实告知并建议用户缩小文件或分段上传");
        }
        return ToolSupport.secureJson(result, identity);
    }

    /** 状态人话标签（SYS_PROMPT 第 8 条口径：禁抛枚举码；PARSING=G4 批G 新中间态同归解析中） */
    private static String statusLabel(DcsAssistantAttachment row) {
        return switch (row.getParseStatus() == null ? "" : row.getParseStatus()) {
            case "PARSED" -> "已解析";
            case "TRUNCATED" -> "已解析（过长截取）";
            case "FAILED" -> "解析失败";
            default -> "解析中";
        };
    }

    private static String safeError(DcsAssistantAttachment row) {
        String err = row.getParseError();
        return err == null || err.isBlank() ? "格式不受支持或文件损坏" : err;
    }
}
