package org.dromara.assistant.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantModelSetting;
import org.dromara.assistant.mapper.AssistantModelSettingMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.DcsAiConfigMapper;
import com.smartdock.service.IDcsAiConfigService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 助手模型设置服务（批3，2026-10-09，Q5/Q11）：助手默认槽的读写 + resolver 链供值。
 *
 * <p>每租户一行 upsert（AssistantConfigService 同款部分提交语义：null=保持原值）。
 * 保存默认指向时校验目标=dcs_ai_config 本租户可用 CHAT 行（enabled='1'），防「默认指向
 * 调不动的模型」；后续行被停用/删除由 AssistantModelFactory 解析时静默回退接口默认，
 * 此处不做级联。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantModelSettingService {

    private final AssistantModelSettingMapper settingMapper;
    private final DcsAiConfigMapper aiConfigMapper;
    private final IDcsAiConfigService aiConfigService;
    private final CapabilityService capabilityService;

    /** 管理端读视图（无行=null 字段取默认；批1015c Q1 增后台默认槽；Q6-A 增后台调用参数） */
    public record SettingView(Long assistantDefaultConfigId, Long backendDefaultConfigId,
                              String allowUserSwitch, String switchScope,
                              String backendThinkingTier, Integer backendMaxTokens) {
    }

    /** 读租户设置行（每租户一行；无行返回默认视图而非 null——前端直接渲染） */
    public SettingView view(String tenantId) {
        DcsAssistantModelSetting row = raw(tenantId);
        if (row == null) {
            return new SettingView(null, null, "0", null, null, null);
        }
        return new SettingView(row.getAssistantDefaultConfigId(), row.getBackendDefaultConfigId(),
            row.getAllowUserSwitch() == null ? "0" : row.getAllowUserSwitch(),
            row.getSwitchScope(),
            row.getBackendThinkingTier(), row.getBackendMaxTokens());
    }

    /** 行原文（无行=null） */
    public DcsAssistantModelSetting raw(String tenantId) {
        return TenantHelper.ignore(() -> settingMapper.selectOne(
            new LambdaQueryWrapper<DcsAssistantModelSetting>()
                .eq(DcsAssistantModelSetting::getTenantId, tenantId)
                .last("limit 1")));
    }

    /**
     * 助手默认 config_id（resolver 优先级链第二段供值）：无行/空槽=null，
     * 调用方据此回退接口默认。行指向失效（被停用/删除）不在此处过滤——
     * AssistantModelFactory 统一按「行存在且可用」裁决后回退，口径单点。
     */
    public Long findAssistantDefaultConfigId(String tenantId) {
        DcsAssistantModelSetting row = raw(tenantId);
        return row == null ? null : row.getAssistantDefaultConfigId();
    }

    /** 允许用户自选开关（Q5 成本闸门，默认关）：无行=0 */
    public boolean allowUserSwitch(String tenantId) {
        return "1".equals(view(tenantId).allowUserSwitch());
    }

    /**
     * 业务批5 回修（glm53 P2-2/dspro P2-2）：一次 raw() 同取白名单+默认指向——AGUI 热路径
     * 建侧（AgentFactory）与比侧（Resolver）原来各查两次同表 selectOne，且两次独立读间改值
     * 会产生"半新半旧指纹"（至多多一次重建，自愈但可消除）；组合读天然同帧一致。
     */
    public record ScopeAndDefault(java.util.Set<Long> scopeIds, Long defaultConfigId) {
    }

    public ScopeAndDefault scopeAndDefault(String tenantId) {
        SettingView v = view(tenantId);
        return new ScopeAndDefault(parseScopeIds(tenantId, v.switchScope()), v.assistantDefaultConfigId());
    }

    /** 白名单集合便捷读（既有 API 保持；内部走组合读不重复查库） */
    public java.util.Set<Long> switchScopeIds(String tenantId) {
        return scopeAndDefault(tenantId).scopeIds();
    }

    /** switch_scope JSON 串 → 白名单集合（空/坏=空集=不限；坏数据告警 fail-open，批4 Q12-A 口径） */
    private java.util.Set<Long> parseScopeIds(String tenantId, String scope) {
        if (scope == null || scope.isBlank()) {
            return java.util.Set.of();
        }
        try {
            java.util.List<Long> ids = org.dromara.common.json.utils.JsonUtils.parseArray(scope, Long.class);
            return ids == null ? java.util.Set.of() : new java.util.HashSet<>(ids);
        } catch (Exception e) {
            // 业务批4 Q12-A（2026-10-13 拍板）：fail-open 行为维持（用户不被锁死），但告警升 error 级
            // 并带租户号——管理员配坏 JSON 时限制静默失效，巡检 sys-error 能抓到失效时刻
            log.error("[ModelSetting] switch_scope 解析失败按不限处理(管理员配坏白名单, 限制已静默失效!) tenant={} scope={}",
                tenantId, scope, e);
            return java.util.Set.of();
        }
    }

    /**
     * 管理端保存（部分提交：null=保持原值；首插 allowUserSwitch 缺省 '0'）。
     * switchScope=null=保持原值；空串=显式清空（库里存 ''，读取侧 isBlank 判不限）。
     * 批1015c Q1：backendDefaultConfigId 同款部分提交——null=保持原值，0=显式清空
     * （回跟随接口默认）；>0 时与助手默认同闸校验（可用 CHAT 行 + CNY 可跑）。
     * 业务反馈 #14 追加 Q6-A（2026-10-16）：backendThinkingTier（语义键 FAST/BALANCED/DEEP；
     * null=保持，空串=显式清空）+ backendMaxTokens（null=保持，0=显式不限制，>0 校验
     * ≤模型能力上限）。槽有效指向模型时按 capability 校验档位合法性；换模型后旧档位
     * 由保存方（画像设置）重选，非法组合在此拒绝。
     */
    public SettingView update(String tenantId, Long assistantDefaultConfigId, String allowUserSwitch,
                              String switchScope, Long backendDefaultConfigId,
                              String backendThinkingTier, Integer backendMaxTokens) {
        if (assistantDefaultConfigId == null) {
            throw new ServiceException("assistantDefaultConfigId 不能为空（助手默认模型必须指定）");
        }
        validateChatConfig(tenantId, assistantDefaultConfigId, "助手默认");
        if (backendDefaultConfigId != null && backendDefaultConfigId > 0) {
            validateChatConfig(tenantId, backendDefaultConfigId, "后台 AI 默认");
        }
        DcsAssistantModelSetting existing = raw(tenantId);
        // 生效后台槽（本次传值优先，其次存量），参数校验与档位翻译都以它为准
        Long effectiveBackendId = backendDefaultConfigId != null ? backendDefaultConfigId
            : (existing == null ? null : existing.getBackendDefaultConfigId());
        String effectiveTier = backendThinkingTier != null ? backendThinkingTier
            : (existing == null ? null : existing.getBackendThinkingTier());
        Integer effectiveMaxTokens = backendMaxTokens != null ? backendMaxTokens
            : (existing == null ? null : existing.getBackendMaxTokens());
        if (StrUtil.isNotBlank(effectiveTier)) {
            validateBackendTier(tenantId, effectiveBackendId, effectiveTier);
        }
        if (effectiveMaxTokens != null && effectiveMaxTokens < 0) {
            throw new ServiceException("最大输出不能为负数（0=不限制）");
        }
        if (effectiveMaxTokens != null && effectiveMaxTokens > 0) {
            validateBackendMaxTokens(tenantId, effectiveBackendId, effectiveMaxTokens);
        }
        DcsAssistantModelSetting merge = new DcsAssistantModelSetting();
        merge.setId(existing == null ? IdWorker.getId() : existing.getId());
        merge.setTenantId(tenantId);
        merge.setAssistantDefaultConfigId(assistantDefaultConfigId);
        // 0=显式清空（存 0，解析侧 <=0 视为跟随接口默认）；null=保持原值
        merge.setBackendDefaultConfigId(backendDefaultConfigId != null ? backendDefaultConfigId
            : (existing == null ? null : existing.getBackendDefaultConfigId()));
        merge.setAllowUserSwitch(allowUserSwitch != null ? allowUserSwitch
            : (existing == null ? "0" : existing.getAllowUserSwitch()));
        // 批8-3 回修（dsflash P1-2）：null=保持原值（部分提交语义，与类注释对齐）——只改默认指向的
        // 保存不得抹掉已配 scope；显式清空传空串。业务批4 dsflash P2 核验后不改值：mapper 的
        // COALESCE 把 null 当"保持原值"，空串是唯一能显式清空的载体（存 '' 后 switchScopeIds
        // 的 isBlank 判不限）——文档口径以本注释为准，勿改成存 NULL（会弄坏清空）
        merge.setSwitchScope(switchScope != null ? switchScope
            : (existing == null ? null : existing.getSwitchScope()));
        // Q6-A 同款口径：tier null=保持 / 空串=显式清空；maxTokens null=保持 / 0=显式不限制
        // dspro 三审 P2 回修: 不回填存量快照, null 直接交 mapper COALESCE 保持——
        // 回填会把本线程读到的旧值写回, 并发窗口内别人的清空/改值被"复活"
        merge.setBackendThinkingTier(backendThinkingTier);
        merge.setBackendMaxTokens(backendMaxTokens);
        stampAudit(merge, existing == null);
        TenantHelper.ignore(() -> settingMapper.upsert(merge));
        return view(tenantId);
    }

    /** Q6-A：后台思考档合法性——语义键枚举 + 按槽模型能力目录（NONE 拒绝；SWITCH 允许存但后台链暂不注入）。 */
    private void validateBackendTier(String tenantId, Long backendConfigId, String tier) {
        if (!List.of("FAST", "BALANCED", "DEEP").contains(tier)) {
            throw new ServiceException("思考档只允许 FAST/BALANCED/DEEP（收到：" + tier + "）");
        }
        DcsAiConfig cfg = backendConfigById(tenantId, backendConfigId);
        if (cfg == null) {
            // 槽未指模型（跟随接口默认）：档位可存，解析侧对实际模型按能力目录翻译，非法组合不注入
            return;
        }
        CapabilityService.CapabilityVo cap = capabilityService.matchCapability(
            tenantId, cfg.getProvider(), cfg.getModelName());
        if ("NONE".equals(cap.getTierKind())) {
            throw new ServiceException("当前后台模型（" + cfg.getModelName() + "）不支持思考参数，请清空思考档位");
        }
    }

    /** Q6-A：最大输出上限——已知模型能力上限时校验不越界（未核实=目录带出，跳过校验）。 */
    private void validateBackendMaxTokens(String tenantId, Long backendConfigId, Integer maxTokens) {
        DcsAiConfig cfg = backendConfigById(tenantId, backendConfigId);
        if (cfg == null) {
            return;
        }
        CapabilityService.CapabilityVo cap = capabilityService.matchCapability(
            tenantId, cfg.getProvider(), cfg.getModelName());
        if (cap.getMaxOutputTokens() != null && maxTokens > cap.getMaxOutputTokens()) {
            throw new ServiceException("最大输出不能超过该模型上限（" + cap.getMaxOutputTokens() + " token/次）");
        }
    }

    /** 取槽指向的配置行（槽空/失效返回 null——校验侧按"跟随接口默认"宽松处理）。 */
    private DcsAiConfig backendConfigById(String tenantId, Long backendConfigId) {
        if (backendConfigId == null || backendConfigId <= 0) {
            return null;
        }
        return TenantHelper.ignore(() -> aiConfigMapper.selectOne(
            new LambdaQueryWrapper<DcsAiConfig>()
                .eq(DcsAiConfig::getConfigId, backendConfigId)
                .eq(DcsAiConfig::getTenantId, tenantId)
                .last("limit 1")));
    }

    /**
     * 槽指向校验（助手默认/后台 AI 默认共用，批1015c Q1 抽取）：①本租户+可用+CHAT 行
     * （防指向停用行/别租户行/嵌入行）；②「真的能跑」闸——listAssistantSelectableWithPrice
     * 同口径（FIXED_PER_CALL 单价 0 豁免，其余须价格目录 CNY 行）。业务批5 Q3-A ② 引入。
     */
    private void validateChatConfig(String tenantId, Long configId, String slotName) {
        Long n = TenantHelper.ignore(() -> aiConfigMapper.selectCount(
            new LambdaQueryWrapper<DcsAiConfig>()
                .eq(DcsAiConfig::getConfigId, configId)
                .eq(DcsAiConfig::getTenantId, tenantId)
                .eq(DcsAiConfig::getEnabled, "1")
                .eq(DcsAiConfig::getUseType, "CHAT")));
        if (n == null || n == 0) {
            throw new ServiceException("所选模型不是本租户可用的 AI 调用模型，请重新选择");
        }
        boolean runnable = aiConfigService.listAssistantSelectableWithPrice(tenantId, "CHAT").stream()
            .anyMatch(vo -> configId.equals(vo.getConfigId()));
        if (!runnable) {
            throw new ServiceException(slotName + "所选模型还没有人民币价格，调不动——请先到「AI 配置 → 价格目录」"
                + "为它配一条人民币价格（免费模型可配 0 元），或换个已配价的模型（若刚配过价格，请稍等几秒重试）");
        }
    }

    /**
     * P2 修复（2026-10-09 Q3）：审计盖章——首插记 create_by/create_dept，更新记 update_by。
     * 管理端保存必在登录态；测试/后台线程无登录上下文时静默落 null（勿让审计反噬主流程）。
     */
    private void stampAudit(DcsAssistantModelSetting merge, boolean firstInsert) {
        try {
            Long userId = LoginHelper.getUserId();
            if (userId == null) {
                return;
            }
            if (firstInsert) {
                merge.setCreateBy(userId);
                org.dromara.common.core.domain.model.LoginUser lu = LoginHelper.getLoginUser();
                if (lu != null && lu.getDeptId() != null) {
                    merge.setCreateDept(lu.getDeptId());
                }
            }
            merge.setUpdateBy(userId);
        } catch (Exception e) {
            log.debug("setting 审计盖章跳过（无登录上下文）：{}", e.getMessage());
        }
    }
}
