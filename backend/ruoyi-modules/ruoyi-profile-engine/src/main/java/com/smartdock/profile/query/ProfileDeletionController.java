package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.domain.ProfileDeletionLog;
import com.smartdock.profile.engine.ProfileDeletionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 主体级删除向导(批11 Q2): 预览波及面 → 确认执行 → 审计日志。
 *
 * @author smartdock
 */
@Validated
@RestController
@RequestMapping("/profile/deletion")
@RequiredArgsConstructor
public class ProfileDeletionController {

    private final ProfileDeletionService deletionService;

    @SaCheckPermission("profile:deletion:query")
    @GetMapping("/preview")
    public R<Map<String, Object>> preview(@RequestParam String source,
                                          @RequestParam String subjectType,
                                          @RequestParam String subjectId) {
        return R.ok(deletionService.preview(source, subjectType, subjectId));
    }

    @SaCheckPermission("profile:deletion:edit")
    @Log(title = "画像合规-主体删除", businessType = BusinessType.DELETE)
    @RepeatSubmit(interval = 5000)
    @PostMapping("/execute")
    public R<Map<String, Object>> execute(@Valid @RequestBody ProfileDeletionBo bo) {
        return R.ok(deletionService.execute(bo.getSource(), bo.getSubjectType(),
            bo.getSubjectId(), bo.getConfirmText()));
    }

    @SaCheckPermission("profile:deletion:query")
    @GetMapping("/logs")
    public R<List<ProfileDeletionLog>> logs() {
        return R.ok(deletionService.recentLogs());
    }
}
