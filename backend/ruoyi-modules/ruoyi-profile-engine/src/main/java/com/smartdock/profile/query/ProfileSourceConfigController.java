package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.engine.ProfileSourceConfigService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源显示配置(改名/停用)——画像方向第二批六件套②。
 *
 * <p>管理面挂在引擎运维·引擎管理页签「来源管理」: 看清单跟随引擎管理查询权限,
 * 改名/停用走独立写权限 profile:source:edit。</p>
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile/source-config")
@RequiredArgsConstructor
public class ProfileSourceConfigController {

    private final ProfileSourceConfigService configService;

    /**
     * 管理面源清单: 主体源∪事件源∪已配置源(含停用), 附计数与显示配置。
     */
    @SaCheckPermission("profile:engine:query")
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list() {
        String tenantId = configService.currentTenant();
        if (tenantId == null) {
            return R.ok(List.of());
        }
        return R.ok(configService.listForAdmin(tenantId));
    }

    /**
     * 改名/停用(覆盖式保存)。
     */
    @SaCheckPermission("profile:source:edit")
    @Log(title = "画像来源配置", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/save")
    public R<Void> save(@RequestBody SourceConfigBo bo) {
        // 与 list 同款防御: 租户上下文缺失时不再往下走(三审 dspro P2)
        String tenantId = configService.currentTenant();
        if (tenantId == null) {
            return R.fail("租户上下文缺失，请重新登录后再试");
        }
        configService.save(tenantId, bo.getSource(), bo.getDisplayName(), bo.getEnabled());
        return R.ok();
    }

    /**
     * 来源配置入参。
     */
    @Data
    public static class SourceConfigBo {
        private String source;
        private String displayName;
        private String enabled;
    }
}
