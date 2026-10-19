package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.domain.ProfileSegment;
import com.smartdock.profile.domain.ProfileSegmentRun;
import com.smartdock.profile.engine.ProfileSegmentService;
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
 * 标签分群接口。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile/segment")
@RequiredArgsConstructor
public class ProfileSegmentController {

    private final ProfileSegmentService segmentService;

    @SaCheckPermission("profile:segment:query")
    @GetMapping("/list")
    public R<List<ProfileSegment>> list(@RequestParam String source) {
        return R.ok(segmentService.list(source));
    }

    @SaCheckPermission("profile:segment:query")
    @GetMapping("/{segmentId}")
    public R<ProfileSegment> get(@PathVariable Long segmentId) {
        return R.ok(segmentService.get(segmentId));
    }

    @SaCheckPermission("profile:segment:edit")
    @Log(title = "画像分群", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<ProfileSegment> create(@RequestBody ProfileSegmentBo bo) {
        return R.ok(segmentService.create(bo));
    }

    @SaCheckPermission("profile:segment:edit")
    @Log(title = "画像分群", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/edit")
    public R<ProfileSegment> edit(@RequestBody ProfileSegmentBo bo) {
        return R.ok(segmentService.update(bo));
    }

    @SaCheckPermission("profile:segment:edit")
    @Log(title = "画像分群", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @PostMapping("/{segmentId}/delete")
    public R<Boolean> delete(@PathVariable Long segmentId) {
        return R.ok(segmentService.remove(segmentId));
    }

    @SaCheckPermission("profile:segment:query")
    @PostMapping("/preview")
    public R<Map<String, Object>> preview(@RequestBody ProfileSegmentBo bo) {
        return R.ok(segmentService.preview(bo));
    }

    @SaCheckPermission("profile:segment:edit")
    @Log(title = "画像分群-评估", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{segmentId}/evaluate")
    public R<ProfileSegmentRun> evaluate(@PathVariable Long segmentId) {
        return R.ok(segmentService.evaluate(segmentId));
    }

    @SaCheckPermission("profile:segment:edit")
    @Log(title = "画像分群-导入", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{segmentId}/import")
    public R<Map<String, Object>> importMembers(@PathVariable Long segmentId,
                                                @RequestBody ProfileSegmentImportBo bo) {
        return R.ok(segmentService.importMembers(segmentId, bo));
    }

    @SaCheckPermission("profile:segment:query")
    @GetMapping("/{segmentId}/members")
    public TableDataInfo<ProfileSegmentMemberRow> members(@PathVariable Long segmentId,
                                                          @RequestParam(required = false) String keyword,
                                                          PageQuery pageQuery) {
        return TableDataInfo.build(segmentService.memberPage(segmentId,
            pageQuery.getPageNum() == null ? 1 : pageQuery.getPageNum(),
            pageQuery.getPageSize() == null ? 20 : pageQuery.getPageSize(),
            keyword));
    }

    @SaCheckPermission("profile:segment:query")
    @GetMapping("/{segmentId}/runs")
    public R<List<ProfileSegmentRun>> runs(@PathVariable Long segmentId) {
        return R.ok(segmentService.runs(segmentId));
    }
}
