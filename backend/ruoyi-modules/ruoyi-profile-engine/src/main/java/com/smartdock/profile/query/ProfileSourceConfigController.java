package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.engine.ProfileSourceConfigService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源显示配置(只读清单)——画像方向第二批六件套②, 单源重构批末收窄为只读。
 *
 * <p>单源重构后「来源管理」写入口(改名/停用)已随页签下线, 这里只留只读清单
 * (引擎运维与 AI 助手工具消费)。</p>
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

}

