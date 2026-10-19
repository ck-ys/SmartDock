package org.dromara.assistant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.domain.AssistantActionLogVo;
import org.dromara.assistant.service.AssistantActionService;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 助手运行记录端点（二站① AU-52，2026-10-08）：管理端「运行记录」只读页签——
 * 动作账本（HITL 确认后的写操作留痕）按租户倒序分页，管理员排错/审计抓手。
 *
 * <p>perm 收敛说明：与「租户开关」同用 dcs:assistant:config（管理页菜单 11928 同源
 * 权限面）——运行记录是管理页的一部分，不单设 action:query（AU-54 权限串对齐原则：
 * 宁可复用真实权限，不再造空挂新串）。路径用 /action/log 与既有用户侧
 * /action/list（断线重连数据源，AssistantConversationController）区分，避免映射冲突。
 * 只读端点无 @Log（查询不留操作日志，与 feedback/list 同口径；动作本身在账本表里
 * 就是留痕主体）。</p>
 */
@Tag(name = "助手运行记录", description = "管理端「运行记录」只读页签——动作账本按租户倒序分页")
@RestController
@RequestMapping("/dcs/assistant")
@RequiredArgsConstructor
public class AssistantActionController extends BaseController {

    private final AssistantActionService actionService;

    /** 管理端运行记录分页（时间/动作/状态/结果/操作人）
     * 2026-10-11 日常查询：keyword 模糊命中 动作/结果摘要/操作人，status 精确，beginTime/endTime 闭区间（yyyy-MM-dd HH:mm:ss） */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/action/log")
    public TableDataInfo<AssistantActionLogVo> list(
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String beginTime,
        @RequestParam(required = false) String endTime) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        List<AssistantActionLogVo> rows = actionService.listLogForTenant(
            LoginHelper.getTenantId(), safeSize, (safePage - 1) * safeSize, keyword, status, beginTime, endTime);
        TableDataInfo<AssistantActionLogVo> data = TableDataInfo.build(rows);
        data.setTotal(actionService.countLogForTenant(LoginHelper.getTenantId(), keyword, status, beginTime, endTime));
        return data;
    }
}
