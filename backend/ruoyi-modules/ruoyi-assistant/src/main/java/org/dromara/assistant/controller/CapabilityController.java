package org.dromara.assistant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.service.CapabilityService;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 模型能力目录端点（2026-10-09 批1，Q13-A）。
 * match 供管理端能力卡与助手输入条共用（OR 权限）；覆盖/恢复仅管理端。
 */
@Tag(name = "AI 模型能力目录", description = "按模型名解析能力；租户覆盖/恢复官方映射")
@RestController
@RequestMapping("/dcs/ai/capability")
@RequiredArgsConstructor
public class CapabilityController extends BaseController {

    private final CapabilityService capabilityService;

    /** 按模型名解析能力（含兜底；永不 404） */
    @SaCheckPermission(value = {"dcs:ai:query", "dcs:assistant:use"}, mode = SaMode.OR)
    @GetMapping("/match")
    public R<CapabilityService.CapabilityVo> match(@RequestParam String provider,
                                                   @RequestParam String model) {
        return R.ok(capabilityService.matchCapability(LoginHelper.getTenantId(), provider, model));
    }

    /** 保存租户覆盖（✎ 覆盖弹窗；重复保存=覆盖更新，只影响本租户）；铁律#21：PUT→POST（2026-10-16 批17 Q4） */
    @SaCheckPermission("dcs:ai:edit")
    @PostMapping("/override")
    public R<CapabilityService.CapabilityVo> override(@RequestBody CapabilityService.OverrideBo bo) {
        return R.ok(capabilityService.override(LoginHelper.getTenantId(), bo));
    }

    /** 恢复官方映射（删本租户覆盖行，回退 SEED）；铁律#21：DELETE→POST（2026-10-16 批17 Q4） */
    @SaCheckPermission("dcs:ai:edit")
    @PostMapping("/reset")
    public R<Void> reset(@RequestParam String provider, @RequestParam String model) {
        capabilityService.resetToSeed(LoginHelper.getTenantId(), provider, model);
        return R.ok();
    }
}
