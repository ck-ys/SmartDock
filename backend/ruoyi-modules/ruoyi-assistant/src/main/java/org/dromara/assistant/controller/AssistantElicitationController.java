package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.core.AssistantElicitationBridge;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.ratelimiter.annotation.RateLimiter;
import org.dromara.common.ratelimiter.enums.LimitType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * MCP elicitation 反问作答端点（G3-A1）。与权限 HITL 的 resume 契约完全分轨：
 * elicitation 发生在 tools/call 在飞期间（run 保持 RUNNING），答完由桥
 * complete future → SDK 把三态回给 server → 工具调用继续；不经 interruptId、
 * 不进 RunAgentInput.resume（乱塞未知 interruptId 会触发 ResumeCoordinator 契约错误）。
 */
@Tag(name = "助手会话", description = "MCP 外部工具反问作答端点")
@Slf4j
@RestController
@RequestMapping("/dcs/assistant")
@RequiredArgsConstructor
public class AssistantElicitationController extends BaseController {

    private final AssistantElicitationBridge elicitationBridge;

    /**
     * 反问作答。归属三段校验（tenant/user/thread 与挂起快照比对）防跨租户替答；
     * content 是用户手打进外部 server 的数据——序列化 4KB 封顶（server 自校验 schema，
     * 错误信息经 callTool 结果回到模型转述，链路自洽）。
     */
    @SaCheckPermission("dcs:assistant:use")
    @RepeatSubmit(interval = 1000)
    @RateLimiter(key = "elicitation:answer", count = 30, time = 60, limitType = LimitType.DEFAULT)
    @Log(title = "AI助手-外部工具反问作答", businessType = BusinessType.OTHER)
    @PostMapping("/elicitation/answer")
    public R<Void> answer(@RequestBody ElicitAnswerBody body) {
        if (body == null || isBlank(body.getId()) || isBlank(body.getAction())) {
            return R.fail("参数不完整（id / action 必填）");
        }
        String contentJson = null;
        if (body.getContent() != null && !body.getContent().isEmpty()) {
            try {
                contentJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body.getContent());
            } catch (Exception e) {
                return R.fail("content 不是合法 JSON 对象");
            }
            if (contentJson.length() > 4096) {
                return R.fail("回答内容过长（上限 4KB）");
            }
        }
        String err = elicitationBridge.answer(
            TenantHelper.getTenantId(), LoginHelper.getUserId(),
            body.getThreadId(), body.getId(), body.getAction(), body.getContent());
        return err == null ? R.ok() : R.fail(err);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 答题体：action ∈ accept / decline / cancel（对齐 MCP ElicitResult 三态）。
     *  不用 record：本模块编译链下嵌套 record 的隐式 accessor 未生成（同包 VO record
     *  仅作出参不经编译期调用故未暴露），入参 BO 显式 getter 最稳 */
    @lombok.Data
    public static class ElicitAnswerBody {
        private String id;
        private String threadId;
        private String action;
        private Map<String, Object> content;
    }
}
