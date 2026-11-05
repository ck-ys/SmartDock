package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.core.AssistantSkillGateMiddleware;
import org.dromara.assistant.core.SkillAssets;
import org.dromara.assistant.core.ToolRegistry;
import org.dromara.assistant.service.AssistantConfigService;
import org.dromara.assistant.tools.ToolIdentity;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作台设置页·技能详情只读端点（2026-11-05 拍板 A3 / 题2 丙）。
 *
 * <p>为什么不复用 {@code /dcs/assistant/skill/{skillId}}：那是管理端点（perm=config），
 * 成员 403。工作台设置页按 A′ 让成员也能看技能，所以这里与 {@code /dcs/assistant/skills}
 * 同权限（{@code dcs:assistant:use}）、同角色＋工具开关门禁——看得见目录才看得见详情。</p>
 *
 * <p>只读：不写库、不改缓存；正文只回给「当前角色本来就能用」的技能，不会因为猜 id
 * 看到提权技能的内容。返回体不含任何密钥/配置，正文=随应用打包的技能说明书原文。</p>
 */
@Tag(name = "助手技能详情", description = "工作台设置页只读技能详情（成员可读）")
@RestController
@RequestMapping("/dcs/assistant/skills")
@RequiredArgsConstructor
public class AssistantSkillViewerController extends BaseController {

    private final AssistantConfigService configService;

    /** 详情：目录里能看见的技能才回正文；看不见与不存在同一句话（不泄露存在性） */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/{skillId}")
    public R<Map<String, Object>> detail(@PathVariable String skillId) {
        if (skillId == null || !skillId.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            return R.fail("没有这个技能，或当前角色用不了");
        }
        ToolIdentity identity = ToolIdentity.from(LoginHelper.getLoginUser());
        int level = identity.getRoleLevel();
        Map<String, String> gate = configService.effective(identity.getTenantId()).toolGate();
        for (SkillAssets.LoadedSkill s : SkillAssets.skills()) {
            if (!s.id().equals(skillId)) {
                continue;
            }
            String key = AssistantSkillGateMiddleware.gateKey(s.id());
            int effective = ToolRegistry.effectiveMinRoleSingle(key, s.minRole(), gate.get(key));
            // 与目录端点同一判据：off / 提档到当前角色之上 = 不可见
            if (effective < 0 || level < effective) {
                return R.fail("没有这个技能，或当前角色用不了");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", s.id());
            out.put("name", s.name());
            out.put("desc", s.description());
            out.put("minRole", s.minRole() == 0 ? "member" : s.minRole() == 2 ? "admin" : "publisher");
            out.put("body", s.body());
            return R.ok(out);
        }
        return R.fail("没有这个技能，或当前角色用不了");
    }
}
