package com.smartdock.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.assistant.service.ToolViewerService;
import com.smartdock.assistant.service.ToolViewerService.ToolRow;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import com.smartdock.assistant.engine.tools.ToolIdentity;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


/**
 * 工作台设置页·工具清单只读端点（2026-11-05 拍板 题0-4 全甲）。
 *
 * <p>为什么不复用 {@code /assistant/toolgate}：那是管理端点（perm=config），成员 403；
 * 工作台设置页按题 1 甲让全员看到「助手会做哪些事」，所以这里与技能目录同权限
 * （{@code smartdock:assistant:use}）。</p>
 *
 * <p><b>脱敏</b>：不回 tool_gate 覆盖键、不回内部枚举/密钥/请求头；只回展示所需的
 * 中文名、一句人话、分类、风险档、状态、谁能用。状态按「模型实际看不看得见」推导——
 * 租户关了（off）/提到管理员档/租户开关未开（长期记忆、联网 key）都如实写。</p>
 *
 * <p>2026-11-17 结构治理批 Q5：查询与状态推导已下沉 {@link ToolViewerService}，
 * 本端点只做租户解析与返回包装，接口契约不变。</p>
 */
@Tag(name = "助手工具清单", description = "工作台设置页工具只读清单（成员可读）")
@RestController
@RequestMapping("/assistant/tools")
@RequiredArgsConstructor
public class AssistantToolViewerController extends BaseController {

    private final ToolViewerService toolViewerService;

    @SaCheckPermission("smartdock:assistant:use")
    @GetMapping
    public R<List<ToolRow>> list() {
        // 动态租户优先：超管切到某租户时，要显示那个租户的开关与次数，而不是登录时的租户快照
        String dynamicTenantId = TenantHelper.getTenantId();
        String tenantId = StringUtils.isNotBlank(dynamicTenantId)
            ? dynamicTenantId
            : ToolIdentity.from(LoginHelper.getLoginUser()).getTenantId();
        return R.ok(toolViewerService.list(tenantId));
    }
}
