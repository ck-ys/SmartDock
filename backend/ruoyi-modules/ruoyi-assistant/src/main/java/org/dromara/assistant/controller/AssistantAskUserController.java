package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.core.AssistantAskUserBridge;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.ratelimiter.annotation.RateLimiter;
import org.dromara.common.ratelimiter.enums.LimitType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 结构化反问作答端点（题12 案1，2026-10-31）。与 MCP elicitation 桥分轨：这边挂的是
 * 内置 {@code ask_user} 工具的在飞调用，答完 future 完成、工具结果回到模型继续跑；
 * 不进 HITL resume，也不写外部调用审计。
 */
@Tag(name = "助手会话", description = "助手结构化反问作答端点")
@Slf4j
@RestController
@RequestMapping("/dcs/assistant")
@RequiredArgsConstructor
public class AssistantAskUserController extends BaseController {

    private final AssistantAskUserBridge askUserBridge;

    /**
     * 反问作答。归属三段校验（tenant/user/thread 与挂起快照比对）防跨租户替答；
     * answers 只回传用户点选结果，长度在桥侧按题数/选项数天然受限。
     */
    @SaCheckPermission("dcs:assistant:use")
    @RepeatSubmit(interval = 1000)
    @RateLimiter(key = "askuser:answer", count = 30, time = 60, limitType = LimitType.IP)
    @Log(title = "AI助手-结构化反问作答", businessType = BusinessType.OTHER, isSaveRequestData = false)
    @PostMapping("/ask/answer")
    public R<Void> answer(@RequestBody AskAnswerBody body) {
        if (body == null || isBlank(body.getId())) {
            return R.fail("参数不完整（id 必填）");
        }
        String err = askUserBridge.answer(
            LoginHelper.getTenantId(), LoginHelper.getUserId(),
            body.getThreadId(), body.getId(), body.getAction(), body.getAnswers());
        return err == null ? R.ok() : R.fail(err);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 答题体：action ∈ answer / cancel；answers=[{header,question,picked:[label...],other}] */
    @Data
    public static class AskAnswerBody {
        private String id;
        private String threadId;
        private String action;
        private List<Map<String, Object>> answers;
    }
}
