package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.domain.ProfileScoreDimension;
import com.smartdock.profile.engine.ProfileDimensionService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 评分维度治理接口。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile/dimension")
@RequiredArgsConstructor
public class ProfileDimensionController {

    private final ProfileDimensionService dimensionService;

    @SaCheckPermission("profile:score:query")
    @GetMapping("/list")
    public R<List<ProfileScoreDimension>> list(@RequestParam String source) {
        return R.ok(dimensionService.list(source));
    }

    @SaCheckPermission("profile:score:query")
    @GetMapping("/capabilities")
    public R<Map<String, Object>> capabilities() {
        return R.ok(dimensionService.capabilities());
    }

    @SaCheckPermission("profile:score:query")
    @GetMapping("/coverage")
    public R<List<Map<String, Object>>> coverage(@RequestParam String source) {
        return R.ok(dimensionService.coverage(source));
    }

    @SaCheckPermission("profile:score:query")
    @GetMapping("/{dimensionId}")
    public R<ProfileScoreDimension> get(@PathVariable Long dimensionId) {
        return R.ok(dimensionService.get(dimensionId));
    }

    @SaCheckPermission("profile:dimension:edit")
    @Log(title = "画像维度", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<ProfileScoreDimension> create(@RequestBody ProfileDimensionBo bo) {
        return R.ok(dimensionService.create(bo));
    }

    @SaCheckPermission("profile:dimension:edit")
    @Log(title = "画像维度", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/edit")
    public R<ProfileScoreDimension> edit(@RequestBody ProfileDimensionBo bo) {
        return R.ok(dimensionService.update(bo));
    }

    @SaCheckPermission("profile:dimension:edit")
    @Log(title = "画像维度", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @PostMapping("/{dimensionId}/disable")
    public R<Boolean> disable(@PathVariable Long dimensionId) {
        return R.ok(dimensionService.remove(dimensionId));
    }

    @SaCheckPermission("profile:score:query")
    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@RequestBody ProfileDimensionPreviewBo bo) {
        return R.ok(dimensionService.preview(bo));
    }
}
