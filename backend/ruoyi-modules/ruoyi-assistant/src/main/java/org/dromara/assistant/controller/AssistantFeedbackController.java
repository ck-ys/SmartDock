package org.dromara.assistant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.domain.DcsAssistantFeedback;
import org.dromara.assistant.service.AssistantFeedbackService;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 助手 👍👎 反馈端点（批G G6，spec §18）：Q13 toast 确认 + 管理端查询。
 */
@Tag(name = "助手反馈", description = "助手 👍👎 反馈：提交 + 管理端查询")
@RestController
@RequestMapping("/dcs/assistant")
@RequiredArgsConstructor
public class AssistantFeedbackController extends BaseController {

    private final AssistantFeedbackService feedbackService;

    /** 提交反馈（perm=use；messageId 可空=整会话评价） */
    @SaCheckPermission("dcs:assistant:use")
    @Log(title = "AI助手-反馈", businessType = BusinessType.INSERT)
    @PostMapping("/feedback")
    public R<Void> submit(@RequestBody Map<String, Object> body) {
        Long messageId;
        try {
            messageId = body.get("messageId") == null ? null : Long.valueOf(body.get("messageId").toString());
        } catch (NumberFormatException e) {
            throw new ServiceException("消息 ID 不合法", 400);
        }
        feedbackService.submit(LoginHelper.getTenantId(), LoginHelper.getUserId(),
            messageId, String.valueOf(body.get("rating")),
            body.get("note") == null ? null : String.valueOf(body.get("note")));
        return R.ok();
    }

    /** Q10（AI 缺口批）：反馈聚合统计——好评率/点踩原因分布/近4周趋势（days 默认 28） */
    @SaCheckPermission("dcs:assistant:feedback:query")
    @GetMapping("/feedback/stats")
    public R<Map<String, Object>> stats(@RequestParam(defaultValue = "28") int days) {
        return R.ok(feedbackService.stats(LoginHelper.getTenantId(), days));
    }

    /** 管理端反馈列表（perm=feedback:query） */
    @SaCheckPermission("dcs:assistant:feedback:query")
    @GetMapping("/feedback/list")
    public TableDataInfo<DcsAssistantFeedback> list(
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        List<DcsAssistantFeedback> rows = feedbackService.listForTenant(
            LoginHelper.getTenantId(), safeSize, (safePage - 1) * safeSize);
        TableDataInfo<DcsAssistantFeedback> data = TableDataInfo.build(rows);
        data.setTotal(feedbackService.countForTenant(LoginHelper.getTenantId()));
        return data;
    }
}
