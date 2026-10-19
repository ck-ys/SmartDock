package com.smartdock.risk.controller;

import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.risk.config.DcsRolloutProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内容机检运行时开关(收尾批 Q4, 2026-09-23 拍板"页面开关"方案)。
 *
 * <p>口径红线：<b>内存态，不落库</b>——修改只改 DcsRolloutProperties 内存字段，
 * 后端重启后自动恢复 yml/启动参数的默认档(观察模式)。这是刻意取舍(拍板 2026-09-23):
 * 万一开关被遗忘，重启即天然刹车，不会出现"重启了还在拦人"的失控态；若需持久化另立拍板。</p>
 *
 * <p>权限双层(沿用 FP 端点范式, 批2 终审P0 教训——权限字符串挡不住所有授权面)：
 * ①菜单/接口权限 dcs:risk:config；②服务内身份闸 超管∨租户管理员。
 * 每次修改经 @Log 落 sys_oper_log，可查"谁在什么时候开了/关了拦截"。</p>
 *
 * @author smartdock
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/risk/content-config")
public class DcsRiskContentConfigController {

    /** 灰度开关配置(内容机检总开关/拦截开关, 运行时内存态) */
    private final DcsRolloutProperties rolloutProperties;

    /** 开关当前档位(风控配置·内容机检页数据源) */
    @SaCheckPermission(value = {"dcs:risk:config"}, mode = SaMode.OR)
    @GetMapping("/state")
    public R<Map<String, Object>> state() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contentModerationEnabled", rolloutProperties.isContentModerationEnabled());
        data.put("contentBlockEnabled", rolloutProperties.isContentBlockEnabled());
        return R.ok(data);
    }

    /**
     * 修改开关(仅接受两个布尔字段, null 不动)。
     *
     * @param body {contentModerationEnabled?, contentBlockEnabled?}
     * @return 修改后的完整档位
     */
    @SaCheckPermission(value = {"dcs:risk:config"}, mode = SaMode.OR)
    @Log(title = "风控内容机检开关", businessType = BusinessType.UPDATE)
    // 2026-10-16 PUT/DELETE→POST（铁律#21：WAF 只放行 GET/POST）
    @PostMapping("/update")
    public R<Map<String, Object>> update(@RequestBody Map<String, Boolean> body) {
        // 身份闸: 开关影响全局提交链路, 收紧到 超管∨租户管理员(同判误报端点口径)
        if (!LoginHelper.isSuperAdmin() && !LoginHelper.isTenantAdmin()) {
            throw new ServiceException("仅管理员可修改内容机检开关", 403);
        }
        Boolean moderation = body.get("contentModerationEnabled");
        Boolean block = body.get("contentBlockEnabled");
        if (moderation == null && block == null) {
            return R.fail("未指定要修改的开关");
        }
        if (moderation != null) {
            rolloutProperties.setContentModerationEnabled(moderation);
        }
        if (block != null) {
            rolloutProperties.setContentBlockEnabled(block);
        }
        return state();
    }
}
