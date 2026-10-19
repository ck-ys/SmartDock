package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.domain.ProfileScoreRule;
import com.smartdock.profile.engine.ProfileRuleService;
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
 * 权重规则治理接口: 草稿/校验/发布/归档/历史/对比/影响/模板。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile/rule")
@RequiredArgsConstructor
public class ProfileRuleController {

    private final ProfileRuleService ruleService;

    @SaCheckPermission("profile:score:query")
    @GetMapping("/current")
    public R<ProfileScoreRule> current(@RequestParam String source) {
        return R.ok(ruleService.current(source));
    }

    @SaCheckPermission("profile:score:query")
    @GetMapping("/history")
    public R<List<ProfileScoreRule>> history(@RequestParam String source) {
        return R.ok(ruleService.history(source));
    }

    @SaCheckPermission("profile:score:query")
    @GetMapping("/templates")
    public R<List<Map<String, Object>>> templates(@RequestParam String source) {
        return R.ok(ruleService.templates(source));
    }

    @SaCheckPermission("profile:score:query")
    @GetMapping("/{ruleId}")
    public R<ProfileScoreRule> get(@PathVariable Long ruleId) {
        return R.ok(ruleService.get(ruleId));
    }

    @SaCheckPermission("profile:rule:edit")
    @Log(title = "画像权重规则-草稿", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/draft")
    public R<ProfileScoreRule> createDraft(@RequestBody ProfileRuleBo bo) {
        return R.ok(ruleService.createDraft(bo));
    }

    @SaCheckPermission("profile:rule:edit")
    @Log(title = "画像权重规则-草稿", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/draft/edit")
    public R<ProfileScoreRule> updateDraft(@RequestBody ProfileRuleBo bo) {
        return R.ok(ruleService.updateDraft(bo));
    }

    @SaCheckPermission("profile:score:query")
    @GetMapping("/{ruleId}/validate")
    public R<Map<String, Object>> validate(@PathVariable Long ruleId) {
        return R.ok(ruleService.validateDraft(ruleId));
    }

    @SaCheckPermission("profile:score:query")
    @GetMapping("/{ruleId}/diff")
    public R<Map<String, Object>> diff(@PathVariable Long ruleId) {
        return R.ok(ruleService.diff(ruleId));
    }

    @SaCheckPermission("profile:score:query")
    @GetMapping("/{ruleId}/impact")
    public R<Map<String, Object>> impact(@PathVariable Long ruleId) {
        return R.ok(ruleService.impact(ruleId));
    }

    @SaCheckPermission("profile:rule:edit")
    @Log(title = "画像权重规则-发布", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{ruleId}/publish")
    public R<ProfileScoreRule> publish(@PathVariable Long ruleId,
                                       @RequestBody ProfileRulePublishBo bo) {
        return R.ok(ruleService.publish(ruleId, bo));
    }

    @SaCheckPermission("profile:rule:edit")
    @Log(title = "画像权重规则-归档", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/{ruleId}/archive")
    public R<ProfileScoreRule> archive(@PathVariable Long ruleId) {
        return R.ok(ruleService.archive(ruleId));
    }

    @SaCheckPermission("profile:rule:edit")
    @Log(title = "画像权重规则-删除草稿", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @PostMapping("/{ruleId}/delete")
    public R<Boolean> delete(@PathVariable Long ruleId) {
        return R.ok(ruleService.deleteDraft(ruleId));
    }
}
