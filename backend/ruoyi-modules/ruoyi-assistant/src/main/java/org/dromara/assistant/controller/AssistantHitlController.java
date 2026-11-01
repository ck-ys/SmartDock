package org.dromara.assistant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.mapper.AssistantRunMapper;
import org.dromara.assistant.persist.AssistantPendingInterruptCache;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
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
    private final org.dromara.assistant.core.AssistantInstanceContext instanceContext;

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
        // 三审回修（dspro-P1-1 旁证）：租户取动态值，与 conversation/stop 等端点同源——
        // 超管切租户后登录快照仍是原租户，用快照查 run 表恒空=恒 403（新增的 409 分支也不可达）
        String tenantId = TenantHelper.getTenantId();
        Long userId = LoginHelper.getUserId();
        // 归属校验：run 表首绑登记（thread 无任何当前用户 run=非本人会话，403）
        var latest = runMapper.selectByThread(tenantId, threadId, userId, 1);
        if (latest.isEmpty()) {
            return R.fail(403, "会话不属于当前用户");
        }
        List<AssistantPendingInterruptCache.PendingInterrupt> list = pendingInterrupts.peek(threadId);
        if (list == null || list.isEmpty()) {
            // 多实例批 2：本机悬挂缓存空、但这轮 run 在另一台活实例上且是 HITL 打断——
            // 说明确认卡留在那台机器；本机返回空数组会让前端以为没卡（卡死）。回人话 409，
            // 刷新后由 LB 按 X-Thread-Id 粘回原实例即可恢复。
            // 二审回修（三席共识 P1）：必须同时满足「owner 还活着」+「end_reason=interrupt」。
            // 进程重启/实例下线后 owner 已死，卡物理上不存在——再回 409 会让用户每次打开
            // 历史会话都弹红错，且刷新永远好不了（死主不可能再被粘上）；user-stop 等其它
            // INTERRUPTED 来源也没有悬挂卡，一律按「无卡」返回空数组，交给陈旧 ASKING 自愈链。
            String owner = latest.get(0).getOwnerInstance();
            boolean otherInstance = owner != null && !owner.isBlank()
                && !owner.equals(instanceContext.current());
            boolean pendingHitlRun = "INTERRUPTED".equals(latest.get(0).getStatus())
                && "interrupt".equals(latest.get(0).getEndReason());
            if (otherInstance && pendingHitlRun && instanceContext.isAlive(owner)) {
                throw new org.dromara.common.core.exception.ServiceException(
                    "确认卡不在当前服务上，请刷新页面后重试", 409);
            }
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
