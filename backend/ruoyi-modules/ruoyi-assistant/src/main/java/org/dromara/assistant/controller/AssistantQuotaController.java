package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.service.AssistantUserQuotaService;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户额度管理端点（业务批3-③ #16，2026-10-12）。
 *
 * <p>页面读走 perm=dcs:assistant:config（管理页同源），四个操作端点走
 * perm=dcs:assistant:quota:edit（菜单 F 按钮 11935 独立授权——可只发查询权不发操作权）。
 * 操作审计走 @Log（sys_oper_log 标准通道）。</p>
 */
@Tag(name = "AI助手-用户额度", description = "单人上限覆盖/重置/禁用/启用")
@RestController
@RequestMapping("/dcs/assistant/quota")
@RequiredArgsConstructor
public class AssistantQuotaController extends BaseController {

    private final AssistantUserQuotaService quotaService;

    /** 用户额度页数据（分页行：用户/部门/覆盖值/今日回合/本月费用/状态）；pageSize 收口 ≤100 防逐行 Redis 放大 */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/page")
    public R<Map<String, Object>> page(@RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String disabledOnly,
                                       @RequestParam(defaultValue = "1") int pageNum,
                                       @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(quotaService.pageList(LoginHelper.getTenantId(), keyword, disabledOnly,
            Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100)));
    }

    /** 重置某用户今日额度（DEL 当日回合计数键，立即恢复可用） */
    @SaCheckPermission("dcs:assistant:quota:edit")
    @Log(title = "AI助手-额度重置", businessType = BusinessType.UPDATE)
    @PostMapping("/reset")
    public R<Void> reset(@RequestBody Map<String, Object> body) {
        quotaService.resetToday(LoginHelper.getTenantId(), asLong(body.get("userId")));
        return R.ok();
    }

    /** 单人限额覆盖（roundLimit/websearchLimit，null=清空跟随租户默认）；铁律#21：PUT→POST（2026-10-16 批17 Q4） */
    @SaCheckPermission("dcs:assistant:quota:edit")
    @Log(title = "AI助手-额度限额", businessType = BusinessType.UPDATE)
    @PostMapping("/limits")
    public R<Void> limits(@RequestBody Map<String, Object> body) {
        quotaService.updateLimits(LoginHelper.getTenantId(), asLong(body.get("userId")),
            asInteger(body.get("roundLimit")), asInteger(body.get("websearchLimit")));
        return R.ok();
    }

    /** 禁用（该用户全部助手请求被拒，人话 429） */
    @SaCheckPermission("dcs:assistant:quota:edit")
    @Log(title = "AI助手-额度禁用", businessType = BusinessType.UPDATE)
    @PostMapping("/disable")
    public R<Void> disable(@RequestBody Map<String, Object> body) {
        quotaService.setDisabled(LoginHelper.getTenantId(), asLong(body.get("userId")), true);
        return R.ok();
    }

    /** 启用（恢复） */
    @SaCheckPermission("dcs:assistant:quota:edit")
    @Log(title = "AI助手-额度启用", businessType = BusinessType.UPDATE)
    @PostMapping("/enable")
    public R<Void> enable(@RequestBody Map<String, Object> body) {
        quotaService.setDisabled(LoginHelper.getTenantId(), asLong(body.get("userId")), false);
        return R.ok();
    }

    /**
     * 前端 JSON 数值可能带小数位（BigNumber 序列化），统一 toLong。
     * 三席回修（m3 P1-4）：非法数字串转 ServiceException 400 人话，而非 NumberFormatException 500。
     */
    private Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            throw new ServiceException("用户标识格式不正确，请刷新页面后重试", 400);
        }
    }

    private Integer asInteger(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            throw new ServiceException("上限值须为整数", 400);
        }
    }
}
