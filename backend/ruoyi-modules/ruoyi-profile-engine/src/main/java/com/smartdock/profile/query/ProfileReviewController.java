package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.domain.ProfileEngineConfig;
import com.smartdock.profile.domain.ProfileReviewQueue;
import com.smartdock.profile.engine.ProfileEngineConfigService;
import com.smartdock.profile.engine.ProfileReviewService;
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

import java.util.Map;

/**
 * 低分复核队列 + 引擎配置接口。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile/review")
@RequiredArgsConstructor
public class ProfileReviewController {

    private final ProfileReviewService reviewService;
    private final ProfileEngineConfigService configService;

    @SaCheckPermission("profile:review:query")
    @GetMapping("/page")
    public TableDataInfo<ProfileReviewQueue> page(@RequestParam(required = false) String source,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String riskLevel,
                                                  @RequestParam(required = false) String keyword,
                                                  PageQuery pageQuery) {
        return TableDataInfo.build(reviewService.page(source, status, riskLevel, keyword,
            pageQuery.getPageNum() == null ? 1 : pageQuery.getPageNum(),
            pageQuery.getPageSize() == null ? 20 : pageQuery.getPageSize()));
    }

    @SaCheckPermission("profile:review:query")
    @GetMapping("/{reviewId}")
    public R<ProfileReviewQueue> get(@PathVariable Long reviewId) {
        return R.ok(reviewService.get(reviewId));
    }

    @SaCheckPermission("profile:review:edit")
    @Log(title = "画像低分复核-确认", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{reviewId}/confirm")
    public R<ProfileReviewQueue> confirm(@PathVariable Long reviewId,
                                         @RequestBody(required = false) ProfileReviewActionBo bo) {
        return R.ok(reviewService.confirm(reviewId, bo));
    }

    @SaCheckPermission("profile:review:edit")
    @Log(title = "画像低分复核-驳回", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{reviewId}/dismiss")
    public R<ProfileReviewQueue> dismiss(@PathVariable Long reviewId,
                                         @RequestBody(required = false) ProfileReviewActionBo bo) {
        return R.ok(reviewService.dismiss(reviewId, bo));
    }

    @SaCheckPermission("profile:review:edit")
    @Log(title = "画像低分复核-扫描", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/scan")
    public R<Map<String, Object>> scan(@RequestParam String source) {
        return R.ok(reviewService.scan(source));
    }

    @SaCheckPermission("profile:review:query")
    @GetMapping("/config")
    public R<ProfileEngineConfig> config(@RequestParam String source) {
        return R.ok(reviewService.config(source));
    }

    @SaCheckPermission("profile:review:edit")
    @Log(title = "画像引擎配置", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/config/edit")
    public R<ProfileEngineConfig> updateConfig(@RequestBody ProfileEngineConfigBo bo) {
        return R.ok(configService.update(bo));
    }
}
