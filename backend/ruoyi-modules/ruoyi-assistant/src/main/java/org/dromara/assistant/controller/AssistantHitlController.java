package org.dromara.assistant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.mapper.AssistantRunMapper;
import org.dromara.assistant.persist.AssistantPendingInterruptCache;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 助手 HITL 悬挂卡端点（二站-B，C2 清剿后半 2026-10-07）。
 *
 * <p>换设备/刷新后确认卡属会话内存态不随消息回放恢复（BATCH_F 已知缺口）——
 * 本端点查悬挂缓存（与框架 pending 同寿，进程级）重建前端确认卡。归属校验：
 * threadId 必须在 run 表有当前用户登记行，否则 403（防跨用户探测）。</p>
 */
@Tag(name = "助手 HITL 悬挂卡", description = "换设备/刷新后确认卡重建（悬挂卡视图回传）")
@RestController
@RequestMapping("/dcs/assistant/hitl")
@RequiredArgsConstructor
public class AssistantHitlController extends BaseController {

    private final AssistantPendingInterruptCache pendingInterrupts;
    private final AssistantRunMapper runMapper;

    /** 悬挂卡视图（前端 turn.hitl 重建直接可用；argsJson 原样回传由前端 JSON.parse 容错） */
    @Data
    public static class PendingHitlVo {
        private String interruptId;
        private String toolName;
        private String argsJson;
        private String reason;
    }

    /** 该 thread 当前悬挂的确认卡（无悬挂返回空数组=不重建） */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/pending")
    public R<List<PendingHitlVo>> pending(@RequestParam String threadId) {
        String tenantId = LoginHelper.getTenantId();
        Long userId = LoginHelper.getUserId();
        // 归属校验：run 表首绑登记（thread 无任何当前用户 run=非本人会话，403）
        if (runMapper.selectByThread(tenantId, threadId, userId, 1).isEmpty()) {
            return R.fail(403, "会话不属于当前用户");
        }
        List<AssistantPendingInterruptCache.PendingInterrupt> list = pendingInterrupts.peek(threadId);
        if (list == null || list.isEmpty()) {
            return R.ok(List.of());
        }
        List<PendingHitlVo> vos = list.stream().map(p -> {
            PendingHitlVo vo = new PendingHitlVo();
            vo.interruptId = p.id();
            vo.toolName = p.toolName() == null ? "" : p.toolName();
            vo.argsJson = p.argsJson() == null ? "" : p.argsJson();
            vo.reason = p.reason() == null ? "" : p.reason();
            return vo;
        }).toList();
        return R.ok(vos);
    }
}
