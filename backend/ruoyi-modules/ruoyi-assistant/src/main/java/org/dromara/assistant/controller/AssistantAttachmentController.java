package org.dromara.assistant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.domain.DcsAssistantAttachment;
import org.dromara.assistant.service.AssistantAttachmentService;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 助手会话附件端点（附件分析批 ATTACH-1，2026-10-07）。
 * 前端上传两步：①通用 OSS 接口（bizSource=ASSISTANT 免审）拿 ossId
 * ②本端点登记+解析。清单接口供文件卡回放渲染。
 */
@Tag(name = "助手会话附件", description = "OSS 上传两步法第二步：登记+解析+清单回放")
@RestController
@RequestMapping("/dcs/assistant/attachment")
@RequiredArgsConstructor
public class AssistantAttachmentController extends BaseController {

    private final AssistantAttachmentService attachmentService;

    /** 登记请求体（ossId=通用上传返回；threadId=AGUI 会话标识） */
    @Data
    public static class RegisterBo {
        @NotNull(message = "缺少文件标识")
        private Long ossId;
        @NotBlank(message = "缺少会话标识")
        private String threadId;
    }

    /** 登记并同步解析（幂等；返回含 parseStatus/parseError/lineCount 供前端即时提示） */
    @SaCheckPermission("dcs:assistant:use")
    @PostMapping("/register")
    public R<DcsAssistantAttachment> register(@RequestBody RegisterBo bo) {
        DcsAssistantAttachment row = attachmentService.register(
            LoginHelper.getTenantId(), LoginHelper.getUserId(), bo.getThreadId(), bo.getOssId());
        // parsed_text 大字段不回传前端（只回状态；正文由工具通道按需取）
        row.setParsedText(null);
        return R.ok(row);
    }

    /** 会话附件清单（旧→新；文件卡回放渲染，不含正文） */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/list")
    public R<List<DcsAssistantAttachment>> list(@RequestParam String threadId) {
        List<DcsAssistantAttachment> rows = attachmentService.listOwned(
            LoginHelper.getTenantId(), LoginHelper.getUserId(), threadId);
        rows.forEach(r -> r.setParsedText(null));
        return R.ok(rows);
    }

    /** 移除会话附件（AU-05A：腾挂载位；属主软删，超限提示「先移除旧文件」的兑现通道）；铁律#21：DELETE→POST 保持路径（2026-10-16 批17 Q4，前端已同步） */
    @SaCheckPermission("dcs:assistant:use")
    @org.springframework.web.bind.annotation.PostMapping("/{id}")
    public R<Void> remove(@org.springframework.web.bind.annotation.PathVariable Long id) {
        attachmentService.remove(LoginHelper.getTenantId(), LoginHelper.getUserId(), id);
        return R.ok();
    }
}
