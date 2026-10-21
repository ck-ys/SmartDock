package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.identity.ProfileIdentityMerge;
import com.smartdock.profile.identity.ProfileIdentityService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 身份图谱管理接口(批10 Q6): 合并记录查询 + 误合并拆分。
 *
 * <p>拆分是不可逆动作(拆完只能重新合并), 受独立权限 + 二次确认(前端) + 审计留痕。</p>
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile/identity")
@RequiredArgsConstructor
public class ProfileIdentityController {

    private final ProfileIdentityService identityService;

    @SaCheckPermission("profile:identity:query")
    @GetMapping("/merges")
    public R<List<ProfileIdentityMerge>> merges(@RequestParam(required = false) String source,
                                                @RequestParam(required = false) String subject) {
        return R.ok(identityService.merges(source, subject));
    }

    /**
     * 拆分误合并: 匿名来源事件归属回改 + 链接回挂 + 合并时间线窗口回放。
     */
    @SaCheckPermission("profile:identity:edit")
    @Log(title = "身份拆分", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/unmerge/{mergeId}")
    public R<Map<String, Object>> unmerge(@PathVariable Long mergeId) {
        return R.ok(identityService.unmerge(mergeId));
    }
}
