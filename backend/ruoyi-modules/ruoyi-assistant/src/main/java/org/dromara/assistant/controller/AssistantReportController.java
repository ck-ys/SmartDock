package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.service.AssistantReportService;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 一键汇报导出端点（五需求批 R4b / Q7-A）：回答工具条「汇报」→ Word（结论+表格）
 * / Excel（表格明细）二进制流下载。perm 与对话同闸（dcs:assistant:use）——导出的
 * 就是当前用户自己会话里看到的内容，无跨用户数据面。
 */
@RestController
@RequestMapping("/dcs/assistant/report")
@RequiredArgsConstructor
public class AssistantReportController extends BaseController {

    private final AssistantReportService reportService;

    @SaCheckPermission("dcs:assistant:use")
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody AssistantReportService.ReportBody body) {
        if (body == null || body.content() == null || body.content().isBlank()) {
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN)
                .body("回答内容为空，等回答完成后再导出".getBytes(StandardCharsets.UTF_8));
        }
        byte[] bytes = reportService.export(body);
        boolean docx = !"xlsx".equalsIgnoreCase(body.format());
        String name = URLEncoder.encode(reportService.fileName(body, String.valueOf(LoginHelper.getUserId())),
            StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + name)
            .contentType(MediaType.parseMediaType(docx
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(bytes);
    }
}
