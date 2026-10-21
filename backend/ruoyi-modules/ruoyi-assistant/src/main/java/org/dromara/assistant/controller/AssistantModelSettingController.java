package org.dromara.assistant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.service.AssistantModelSettingService;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.vo.DcsAiConfigVo;
import com.smartdock.service.IDcsAiConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 助手模型设置端点（批3，2026-10-09，Q5）：AI 助手管理页「模型选择卡」的数据源。
 * 权限随管理页租户配置族（dcs:assistant:config）。
 */
@Tag(name = "助手模型设置", description = "AI 助手管理页「模型选择卡」的数据源")
@RestController
@RequestMapping("/dcs/assistant/model-setting")
@RequiredArgsConstructor
public class AssistantModelSettingController extends BaseController {

    private final AssistantModelSettingService settingService;
    private final IDcsAiConfigService aiConfigService;

    /** 读本租户助手模型设置（无行=默认视图：无默认指向/自选关/不限范围） */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping
    public R<AssistantModelSettingService.SettingView> get() {
        return R.ok(settingService.view(LoginHelper.getTenantId()));
    }

    /** 本租户可用 CHAT 行+单价（批4 Q5/Q6：选择卡下拉数据源，★默认行排最前） */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/options")
    public R<List<DcsAiConfigVo>> options() {
        return R.ok(aiConfigService.listUsableWithPrice(LoginHelper.getTenantId(), "CHAT"));
    }

    /**
     * 批7 Q5 消费端：用户侧模型自选视图（普通用户权限 dcs:assistant:use，输入条模型选择器数据源）。
     * 开关关时 options 恒空（不暴露模型清单防探测）；开时=本租户可用 CHAT 行（★排最前+单价，
     * apiKey 已掩码），assistantDefaultConfigId 供前端标注「跟随默认」的指向。
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/model-choice")
    public R<Map<String, Object>> modelChoice() {
        String tenantId = LoginHelper.getTenantId();
        AssistantModelSettingService.SettingView setting = settingService.view(tenantId);
        boolean allow = "1".equals(setting.allowUserSwitch());
        // 超级 E2E 2026-10-10 修复：选项过 CNY 闸（与工厂裁决同源）——USD/无价行不进
        // 用户选择器，防「选了 deepseek-chat 实际静默跑默认模型」的 pill 与计费不符。
        // 批B step5 P1-1 回修：清单一律拉取——自选关时 options 仍恒空（防探测），
        // 但要从中解析默认行的 context_window 作 defaultContextWindow 下发，
        // 否则默认租户（allow_user_switch='0'）的上下文环永远拿不到分母
        List<DcsAiConfigVo> usable = aiConfigService.listAssistantSelectableWithPrice(tenantId, "CHAT");
        // 业务批4 Q3：自选范围白名单——管理页圈定了 switch_scope 时 options 只留圈内的行；
        // 空集=不限（全部可用行）。环分母仍从【未过滤】全池解析——管理员圈范围不该连带
        // 吞掉默认行的上下文环（默认指向行保存时已过可用性校验，天然合法）
        java.util.Set<Long> scopeIds = settingService.switchScopeIds(tenantId);
        List<DcsAiConfigVo> scoped = scopeIds.isEmpty() ? usable
            : usable.stream().filter(vo -> scopeIds.contains(vo.getConfigId())).toList();
        List<DcsAiConfigVo> options = List.of();
        if (allow) {
            // 批8-3 回修（glm53 P2-3）：普通用户不需要任何 key 材料——连掩码片段（前7+后3）也不下发；
            // 管理端 /options 保留掩码用于辨识配置行，两侧口径刻意不同
            options = scoped;
            options.forEach(vo -> {
                vo.setApiKey(null);
                vo.setApiKeyMask(null);
            });
        }
        // 环分母（与前端 effectiveRow 回退序一致：assistantDefaultConfigId 指向行 ?? 清单首行）
        Long defId = setting.assistantDefaultConfigId();
        DcsAiConfigVo defRow = defId == null ? null
            : usable.stream().filter(vo -> defId.equals(vo.getConfigId())).findFirst().orElse(null);
        if (defRow == null && !usable.isEmpty()) {
            defRow = usable.get(0);
        }
        Integer defaultContextWindow = defRow == null ? null : defRow.getContextWindow();
        // 业务批5 回修（dspro P1-1）：默认行"真不可用"用未过滤 usable 判——管理员把默认行裁出
        // 自选白名单时 options 不含它，但实际调用走默认链直通正常；前端只凭 options 会假告警
        boolean defaultSelectable = defId == null
            || usable.stream().anyMatch(vo -> defId.equals(vo.getConfigId()));
        // Map.of 不收 null 值：-1=未知哨兵，前端约定 <=0 视作 null（环走待测分支）
        return R.ok(Map.of(
            "allowUserSwitch", setting.allowUserSwitch(),
            "assistantDefaultConfigId", defId == null ? "" : defId,
            "defaultSelectable", defaultSelectable,
            "defaultContextWindow", defaultContextWindow == null ? -1 : defaultContextWindow,
            // G2 问题②：自选关时 options 恒空（防探测），用户侧只剩纯 id 拿不到名字——
            // 默认行名字一并下发（只读徽标展示用；defRow 已解析零额外查询，非敏感字段）
            "defaultModelName", defRow == null || defRow.getModelName() == null ? "" : defRow.getModelName(),
            "options", options
        ));
    }

    /**
     * 保存助手默认模型与自选开关（部分提交：null=保持原值）。
     * body: { assistantDefaultConfigId, allowUserSwitch, switchScope, backendDefaultConfigId,
     *         backendThinkingTier, backendMaxTokens }
     * 批1015c Q1：backendDefaultConfigId 0=显式清空（回跟随接口默认），null=保持原值。
     * 业务反馈 #14 追加 Q6-A（2026-10-16）：backendThinkingTier（FAST/BALANCED/DEEP，
     * 空串=显式清空）+ backendMaxTokens（0=显式不限制，null=保持）。
     * 铁律#21（2026-10-16 客户 WAF 只放行 GET/POST）：批17 Q4 收口为 POST 专用——
     * 同方法挂 POST 根路径 + POST /edit 双通道（前端 api/dcs/ai:40 已改 /edit），
     * 旧 PUT 撤除（与批17 全站 DELETE/PUT 清零口径一致）。
     */
    @SaCheckPermission("dcs:assistant:config")
    @PostMapping({"", "/edit"})
    public R<AssistantModelSettingService.SettingView> save(@RequestBody Map<String, Object> body) {
        Long configId;
        try {
            configId = body.get("assistantDefaultConfigId") == null ? null
                : Long.valueOf(String.valueOf(body.get("assistantDefaultConfigId")));
        } catch (NumberFormatException e) {
            // 批8-3 回修（glm53 P2-4）：非数字直连 API 请求转 400 人话而非 500
            throw new org.dromara.common.core.exception.ServiceException("assistantDefaultConfigId 必须是数字");
        }
        Long backendDefaultConfigId;
        try {
            backendDefaultConfigId = body.get("backendDefaultConfigId") == null ? null
                : Long.valueOf(String.valueOf(body.get("backendDefaultConfigId")));
        } catch (NumberFormatException e) {
            throw new org.dromara.common.core.exception.ServiceException("backendDefaultConfigId 必须是数字");
        }
        Integer backendMaxTokens;
        try {
            backendMaxTokens = body.get("backendMaxTokens") == null ? null
                : Integer.valueOf(String.valueOf(body.get("backendMaxTokens")));
        } catch (NumberFormatException e) {
            throw new org.dromara.common.core.exception.ServiceException("backendMaxTokens 必须是数字");
        }
        String allowSwitch = body.get("allowUserSwitch") == null ? null
            : String.valueOf(body.get("allowUserSwitch"));
        String scope = body.get("switchScope") == null ? null
            : String.valueOf(body.get("switchScope"));
        String tier = body.get("backendThinkingTier") == null ? null
            : String.valueOf(body.get("backendThinkingTier"));
        return R.ok(settingService.update(LoginHelper.getTenantId(), configId, allowSwitch, scope,
            backendDefaultConfigId, tier, backendMaxTokens));
    }
}
