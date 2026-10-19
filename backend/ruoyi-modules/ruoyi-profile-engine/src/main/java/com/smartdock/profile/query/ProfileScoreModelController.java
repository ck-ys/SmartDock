package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.domain.ProfileModelRun;
import com.smartdock.profile.domain.ProfileScoreModel;
import com.smartdock.profile.engine.ProfileScoreModelService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
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
 * 通用评分模型 / RFM 接口。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile/model")
@RequiredArgsConstructor
public class ProfileScoreModelController {

    private final ProfileScoreModelService modelService;

    @SaCheckPermission("profile:model:query")
    @GetMapping("/list")
    public R<List<ProfileScoreModel>> list(@RequestParam String source) {
        return R.ok(modelService.list(source));
    }

    @SaCheckPermission("profile:model:query")
    @GetMapping("/preset")
    public R<Map<String, Object>> preset() {
        return R.ok(modelService.rfmPreset());
    }

    @SaCheckPermission("profile:model:query")
    @GetMapping("/{modelId}")
    public R<ProfileScoreModel> get(@PathVariable Long modelId) {
        return R.ok(modelService.get(modelId));
    }

    @SaCheckPermission("profile:model:edit")
    @Log(title = "画像评分模型", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<ProfileScoreModel> create(@RequestBody ProfileScoreModelBo bo) {
        return R.ok(modelService.create(bo));
    }

    @SaCheckPermission("profile:model:edit")
    @Log(title = "画像评分模型", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/edit")
    public R<ProfileScoreModel> edit(@RequestBody ProfileScoreModelBo bo) {
        return R.ok(modelService.update(bo));
    }

    @SaCheckPermission("profile:model:edit")
    @Log(title = "画像评分模型", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @PostMapping("/{modelId}/delete")
    public R<Boolean> delete(@PathVariable Long modelId) {
        return R.ok(modelService.remove(modelId));
    }

    @SaCheckPermission("profile:model:edit")
    @Log(title = "画像评分模型-运行", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{modelId}/run")
    public R<ProfileModelRun> run(@PathVariable Long modelId) {
        return R.ok(modelService.run(modelId, "MANUAL"));
    }

    @SaCheckPermission("profile:model:query")
    @GetMapping("/{modelId}/overview")
    public R<Map<String, Object>> overview(@PathVariable Long modelId) {
        return R.ok(modelService.overview(modelId));
    }

    @SaCheckPermission("profile:model:query")
    @GetMapping("/{modelId}/subjects")
    public TableDataInfo<ProfileModelSubjectRow> subjects(@PathVariable Long modelId,
                                                          @RequestParam(required = false) String level,
                                                          @RequestParam(required = false) String keyword,
                                                          PageQuery pageQuery) {
        return TableDataInfo.build(modelService.subjectPage(modelId,
            pageQuery.getPageNum() == null ? 1 : pageQuery.getPageNum(),
            pageQuery.getPageSize() == null ? 20 : pageQuery.getPageSize(),
            level, keyword));
    }

    @SaCheckPermission("profile:model:query")
    @GetMapping("/{modelId}/subject/{subjectId}")
    public R<Map<String, Object>> subject(@PathVariable Long modelId,
                                          @PathVariable String subjectId,
                                          @RequestParam(defaultValue = "user") String subjectType) {
        return R.ok(modelService.subjectDetail(modelId, subjectType, subjectId));
    }

    @SaCheckPermission("profile:model:query")
    @GetMapping("/{modelId}/trend")
    public R<List<Map<String, Object>>> trend(@PathVariable Long modelId,
                                              @RequestParam(defaultValue = "30") int days) {
        return R.ok(modelService.trend(modelId, days));
    }
}
