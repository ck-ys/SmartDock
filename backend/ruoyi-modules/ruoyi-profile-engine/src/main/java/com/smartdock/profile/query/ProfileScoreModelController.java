package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.domain.ProfileModelRun;
import com.smartdock.profile.domain.ProfileScoreModel;
import com.smartdock.profile.domain.ProfileModelConfigSnapshot;
import com.smartdock.profile.engine.ProfileScoreModelService;
import com.smartdock.profile.query.ProfileModelPublishBo;
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

    /** 运行记录列表(版本对比选材; 只读)。 */
    @SaCheckPermission("profile:model:query")
    @GetMapping("/{modelId}/runs")
    public R<List<ProfileModelRun>> runs(@PathVariable Long modelId) {
        return R.ok(modelService.runs(modelId));
    }

    /** 版本对比: 两个已完成 run 的逐主体分数/分层差异(批9 Q1-A)。 */
    @SaCheckPermission("profile:model:query")
    @GetMapping("/{modelId}/compare")
    public R<Map<String, Object>> compare(@PathVariable Long modelId,
                                          @RequestParam Long runA,
                                          @RequestParam Long runB) {
        return R.ok(modelService.compare(modelId, runA, runB));
    }

    /** 试算预览: 草稿配置影子计算, 新旧分布+PSI+样本量(批9 Q6-A)。 */
    @SaCheckPermission("profile:model:query")
    @PostMapping("/{modelId}/dry-run")
    public R<Map<String, Object>> dryRun(@PathVariable Long modelId) {
        return R.ok(modelService.dryRun(modelId));
    }

    /** 发布: 版本落定+配置存档+触发重算(读侧只认已完成 run, 不混拼)。 */
    @SaCheckPermission("profile:model:edit")
    @Log(title = "画像模型发布", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{modelId}/publish")
    public R<Map<String, Object>> publish(@PathVariable Long modelId,
                                          @RequestBody(required = false) ProfileModelPublishBo bo) {
        return R.ok(modelService.publish(modelId, bo));
    }

    /** 一键回滚: 存档版本配置写回并重算。 */
    @SaCheckPermission("profile:model:edit")
    @Log(title = "画像模型回滚", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{modelId}/rollback")
    public R<Map<String, Object>> rollback(@PathVariable Long modelId,
                                           @RequestParam String version) {
        return R.ok(modelService.rollback(modelId, version));
    }

    /** 配置版本存档列表(历史 diff/回滚选材)。 */
    @SaCheckPermission("profile:model:query")
    @GetMapping("/{modelId}/config-snapshots")
    public R<List<ProfileModelConfigSnapshot>> configSnapshots(@PathVariable Long modelId) {
        return R.ok(modelService.configSnapshots(modelId));
    }

    @GetMapping("/{modelId}/trend")
    public R<List<Map<String, Object>>> trend(@PathVariable Long modelId,
                                              @RequestParam(defaultValue = "30") int days) {
        return R.ok(modelService.trend(modelId, days));
    }
}
