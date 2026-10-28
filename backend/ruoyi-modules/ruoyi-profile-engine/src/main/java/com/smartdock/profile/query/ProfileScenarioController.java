package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.profile.engine.ProfileScenarioService;
import com.smartdock.profile.model.ScenarioTemplateDef;
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

import java.util.Map;

/**
 * 场景模板库(F 片): 目录/套用/导入/导出/删除。
 *
 * @author smartdock
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/profile/scenario")
public class ProfileScenarioController {

    private final ProfileScenarioService scenarioService;

    /** 模板目录: 推荐场景(内置) + 我的模板(本租户导入)。 */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/templates")
    public R<Map<String, Object>> templates() {
        return R.ok(scenarioService.catalog());
    }

    /** 套用模板(维度/分群/漏斗跳过已存在, 权重只生成草稿)。 */
    @SaCheckPermission("profile:scenario:edit")
    @RepeatSubmit
    @Log(title = "场景模板", businessType = BusinessType.INSERT)
    @PostMapping("/apply")
    public R<Map<String, Object>> apply(@RequestBody ScenarioApplyBo bo) {
        return R.ok(scenarioService.apply(bo.getCode(), bo.getTemplateId(), bo.getSubjectType()));
    }

    /** 导入模板 JSON, 落「我的模板」。 */
    @SaCheckPermission("profile:scenario:edit")
    @RepeatSubmit
    @Log(title = "场景模板", businessType = BusinessType.IMPORT)
    @PostMapping("/import")
    public R<Map<String, Object>> importTemplate(@RequestBody ScenarioTemplateDef def) {
        return R.ok(scenarioService.importTemplate(def));
    }

    /** 导出模板定义(内置按 code, 导入按 templateId)。 */
    @SaCheckPermission("profile:score:query")
    @Log(title = "场景模板", businessType = BusinessType.EXPORT)
    @GetMapping("/export")
    public R<ScenarioTemplateDef> export(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) Long templateId) {
        return R.ok(scenarioService.export(code, templateId));
    }

    /** 删除「我的模板」(POST-delete, 与 segment/funnel 同款——部分客户 WAF 只放行 GET/POST)。 */
    @SaCheckPermission("profile:scenario:edit")
    @Log(title = "场景模板", businessType = BusinessType.DELETE)
    @PostMapping("/{templateId}/delete")
    public R<Boolean> delete(@PathVariable Long templateId) {
        return R.ok(scenarioService.delete(templateId));
    }

    /**
     * 套用入参: 内置模板传 code, 我的模板传 templateId, 至少其一。
     */
    @lombok.Data
    public static class ScenarioApplyBo {
        private String code;
        private Long templateId;
        private String subjectType;
    }
}
