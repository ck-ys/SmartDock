package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.domain.ProfilePiiField;
import com.smartdock.profile.engine.ProfilePiiService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PII 字段清单管理(批11 Q1-A, 合规中心)。清单全局生效(不分租户), 只有持编辑权限的管理员可改。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile/pii")
@RequiredArgsConstructor
public class ProfilePiiFieldController {

    private final ProfilePiiService piiService;

    @SaCheckPermission("profile:pii:query")
    @GetMapping("/fields")
    public R<List<ProfilePiiField>> fields() {
        return R.ok(piiService.listFields());
    }

    @SaCheckPermission("profile:pii:edit")
    @Log(title = "PII字段清单-保存", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/fields")
    public R<ProfilePiiField> save(@RequestBody ProfilePiiField field) {
        return R.ok(piiService.saveField(field));
    }

    @SaCheckPermission("profile:pii:edit")
    @Log(title = "PII字段清单-删除", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @DeleteMapping("/fields/{fieldKey}")
    public R<Void> delete(@PathVariable String fieldKey) {
        piiService.deleteField(fieldKey);
        return R.ok();
    }
}
