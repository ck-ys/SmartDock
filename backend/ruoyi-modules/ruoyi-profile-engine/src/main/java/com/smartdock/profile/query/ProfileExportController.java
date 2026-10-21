package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.domain.ProfileExportLog;
import com.smartdock.profile.engine.ProfileReachService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 导出审计查询(批11 Q5, 合规中心原料)。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile/export")
@RequiredArgsConstructor
public class ProfileExportController {

    private final ProfileReachService reachService;

    @SaCheckPermission("profile:export:query")
    @GetMapping("/logs")
    public R<List<ProfileExportLog>> logs() {
        return R.ok(reachService.exportLogs());
    }
}
