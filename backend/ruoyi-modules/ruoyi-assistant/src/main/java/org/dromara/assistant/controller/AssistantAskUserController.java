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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
        if (err == null) {
            return R.ok();
        }
        // 多实例批 2：卡已失效/不在本会话 = 多半是请求落到了没有挂起现场的另一台实例。
        // 回 HTTP 409 人话（而不是 200 包错误），前端能引导刷新后粘回原实例。
        if (err.contains("已失效") || err.contains("不属于当前会话")) {
            throw new org.dromara.common.core.exception.ServiceException(err, 409);
        }
        return R.fail(err);
    }

    /**
     * 刷新续走（Q4 甲）：返回当前 thread 队首卡，带绝对到期时刻/停表状态；
     * 无卡返回 data=null。权限/归属与作答同源，跨租户查询拿不到别人的卡。
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/ask/pending")
    public R<Map<String, Object>> pending(@RequestParam String threadId) {
        if (isBlank(threadId)) {
            return R.fail("threadId 必填");
        }
        return R.ok(askUserBridge.pendingFor(LoginHelper.getTenantId(), LoginHelper.getUserId(), threadId));
    }

    /**
     * 首次有效交互停表（Q1 甲）：悬停/点选/改字/翻页/点倒计时都调这里；
     * 幂等，卡已失效时按 409 人话返回（前端静默忽略即可）。
     */
    @SaCheckPermission("dcs:assistant:use")
    // 不挂 @RepeatSubmit：停表本身就是幂等操作，框架最小间隔 1 秒，悬停/点选会连续触发
    @PostMapping("/ask/snooze")
    public R<Void> snooze(@RequestBody AskSnoozeBody body) {
        if (body == null || isBlank(body.getId())) {
            return R.fail("参数不完整（id 必填）");
        }
        String err = askUserBridge.snooze(
            LoginHelper.getTenantId(), LoginHelper.getUserId(),
            body.getThreadId(), body.getId());
        if (err == null) {
            return R.ok();
        }
        if (err.contains("已失效") || err.contains("不属于当前会话") || err.contains("排队")) {
            throw new org.dromara.common.core.exception.ServiceException(err, 409);
        }
        return R.fail(err);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 停表体：只需要 threadId + 卡 id */
    @Data
    public static class AskSnoozeBody {
        private String id;
        private String threadId;
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
