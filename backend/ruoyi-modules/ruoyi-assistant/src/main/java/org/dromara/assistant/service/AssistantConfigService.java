package org.dromara.assistant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantConfig;
import org.dromara.assistant.limiter.AssistantAdmissionGate;
import org.dromara.assistant.mapper.AssistantConfigMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.DcsAiConfigMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 助手租户配置服务（批G G1，2026-10-06）：开关三态源头 + 平台默认常量。
 *
 * <p>三态口径（spec §15.5 / §18 status 端点）：
 * 关闭=无配置行或 enabled=0；开启=enabled=1 且该租户有启用的 CHAT 模型行；
 * 灰球=enabled=1 但未配模型（前端悬浮球显示灰色引导，不加载对话面板）。
 * 灰球是派生态不落库——单一事实源 enabled，避免两处开关打架。</p>
 *
 * <p>降级纪律（§6.1 边车不拖主车）：PG 读配置异常→503 话术拒绝，绝不放行无闸通过；
 * degraded 标志由准入闸 Redis 探针派生（Redis 挂=只读降级态）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantConfigService {

    /** 平台默认：每用户每日回合上限（Q5） */
    public static final int PLATFORM_DAILY_ROUNDS = 50;
    /** 平台默认：租户日预算（元， Q16；上线压测后再校准种子） */
    public static final BigDecimal PLATFORM_DAILY_BUDGET = new BigDecimal("50.0000");
    /** 平台默认：长期记忆关（拍板⑥，P1 恒关） */
    public static final String PLATFORM_MEMORY_ENABLED = "0";
    /** 平台默认：压缩触发阈值（60K 档） */
    public static final int PLATFORM_COMPACTION_TOKENS = 60000;
    /** 平台默认：每人每日联网搜索次数上限（业务批3-④ #17，grill Q7A=20 次免费额度） */
    public static final int PLATFORM_WEBSEARCH_LIMIT = 20;
    /** 平台默认：每日摘要关（五需求批 R4b / Q7-C；租户在管理页开） */
    public static final String PLATFORM_DIGEST_ENABLED = "0";
    /** 平台默认：助手对外称呼（快刀批 Q6，2026-10-20；租户可改，前端 8 处硬编码收敛于此） */
    public static final String PLATFORM_ASSISTANT_NAME = "SmartDock 小助手";

    private final AssistantConfigMapper configMapper;
    private final DcsAiConfigMapper aiConfigMapper;
    private final AssistantAdmissionGate admissionGate;

    /** 生效配置视图（行存在与否 + 两级默认合并结果；websearchLimitPerUser 批④起联网配额租户默认；
     *  digestEnabled 五需求批 R4b 起每日摘要开关；assistantName 快刀批 Q6 起助手称呼） */
    public record EffectiveConfig(boolean rowPresent, boolean enabled, int dailyRoundLimitPerUser,
                                  BigDecimal dailyBudgetAmount, String memoryEnabled, int compactionTriggerTokens,
                                  int websearchLimitPerUser, String digestEnabled, String assistantName,
                                  java.util.Map<String, String> toolGate) {
    }

    /** 读租户生效配置：无行=平台默认（默认关闭） */
    public EffectiveConfig effective(String tenantId) {
        DcsAssistantConfig row = TenantHelper.ignore(() -> configMapper.selectById(tenantId));
        if (row == null) {
            return new EffectiveConfig(false, false, PLATFORM_DAILY_ROUNDS,
                PLATFORM_DAILY_BUDGET, PLATFORM_MEMORY_ENABLED, PLATFORM_COMPACTION_TOKENS,
                PLATFORM_WEBSEARCH_LIMIT, PLATFORM_DIGEST_ENABLED, PLATFORM_ASSISTANT_NAME,
                java.util.Map.of());
        }
        return new EffectiveConfig(true, "1".equals(row.getEnabled()),
            row.getDailyRoundLimitPerUser() == null ? PLATFORM_DAILY_ROUNDS : row.getDailyRoundLimitPerUser(),
            row.getDailyBudgetAmount() == null ? PLATFORM_DAILY_BUDGET : row.getDailyBudgetAmount(),
            row.getMemoryEnabled() == null ? PLATFORM_MEMORY_ENABLED : row.getMemoryEnabled(),
            row.getCompactionTriggerTokens() == null ? PLATFORM_COMPACTION_TOKENS : row.getCompactionTriggerTokens(),
            row.getWebsearchLimitPerUser() == null ? PLATFORM_WEBSEARCH_LIMIT : row.getWebsearchLimitPerUser(),
            row.getDigestEnabled() == null ? PLATFORM_DIGEST_ENABLED : row.getDigestEnabled(),
            normalizeName(row.getAssistantName()),
            parseToolGate(row.getToolGate()));
    }

    /** 批1 Q1=B：tool_gate JSON 解析（工具名→off/member/admin）；空/坏 JSON=空表不覆盖 */
    private static java.util.Map<String, String> parseToolGate(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Map.of();
        }
        try {
            java.util.Map<String, Object> parsed = org.dromara.common.json.utils.JsonUtils.parseMap(raw);
            java.util.Map<String, String> gate = new java.util.LinkedHashMap<>();
            parsed.forEach((k, v) -> {
                if (k != null && v != null) {
                    gate.put(k, String.valueOf(v).trim());
                }
            });
            return gate;
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    /** 称呼归一（读侧）：空白一律=平台默认，非空 trim 后直出（写侧已限长） */
    private static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return PLATFORM_ASSISTANT_NAME;
        }
        return raw.trim();
    }

    /**
     * 租户开关闸（resolver 链第二环）：关闭→403 人话；PG 异常→503 降级拒绝。
     */
    public void assertOpen(String tenantId) {
        EffectiveConfig c;
        try {
            c = effective(tenantId);
        } catch (Exception e) {
            log.error("[AssistantConfig] 配置表不可读, 助手降级拒绝 tenant={}", tenantId, e);
            throw new ServiceException("AI 助手暂不可用，请稍后再试", 503);
        }
        if (!c.enabled()) {
            throw new ServiceException("AI 助手未开通，请联系管理员开通后再使用", 403);
        }
    }

    /** 该租户是否已配启用 CHAT 模型行（灰球判定；与 AssistantModelFactory 同口径但轻量不建模型） */
    public boolean modelConfigured(String tenantId) {
        try {
            Long n = TenantHelper.ignore(() -> aiConfigMapper.selectCount(
                new LambdaQueryWrapper<DcsAiConfig>()
                    .eq(DcsAiConfig::getTenantId, tenantId)
                    .eq(DcsAiConfig::getEnabled, "1")
                    .eq(DcsAiConfig::getUseType, "CHAT")));
            return n != null && n > 0;
        } catch (Exception e) {
            log.warn("[AssistantConfig] 模型配置探测失败 tenant={}: {}", tenantId, e.getMessage());
            return false;
        }
    }

    /** 基础设施降级标志（status 端点）：Redis 探针，挂=true（前端展示降级标识） */
    public boolean degraded() {
        return !admissionGate.healthy();
    }

    /** 覆盖行原文（config 读端点回显用） */
    public DcsAssistantConfig raw(String tenantId) {
        return TenantHelper.ignore(() -> configMapper.selectById(tenantId));
    }

    /**
     * 批1 Q1=B（2026-10-21）：工具开关矩阵整列写入（PUT /dcs/assistant/toolgate）。
     * 只动 tool_gate 列——不走 update() 整行合并，避免与联网/称呼校验互相牵连。
     * 无行租户：按平台默认+enabled='0' 首插（保持"未配置=助手关闭"语义不翻车），仅落开关。
     */
    public void updateToolGate(String tenantId, java.util.Map<String, String> gate) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ServiceException("tenantId 不能为空");
        }
        // 清空用 "" 哨兵（MyBatis-Plus updateById 跳过 null 字段写不进；读侧 blank=空表）
        String json = (gate == null || gate.isEmpty()) ? ""
            : org.dromara.common.json.utils.JsonUtils.toJsonString(gate);
        DcsAssistantConfig row = TenantHelper.ignore(() -> configMapper.selectById(tenantId));
        if (row == null) {
            DcsAssistantConfig insert = new DcsAssistantConfig();
            insert.setTenantId(tenantId);
            insert.setEnabled("0");
            insert.setDailyRoundLimitPerUser(PLATFORM_DAILY_ROUNDS);
            insert.setDailyBudgetAmount(PLATFORM_DAILY_BUDGET);
            insert.setMemoryEnabled(PLATFORM_MEMORY_ENABLED);
            insert.setCompactionTriggerTokens(PLATFORM_COMPACTION_TOKENS);
            insert.setWebsearchLimitPerUser(PLATFORM_WEBSEARCH_LIMIT);
            insert.setDigestEnabled(PLATFORM_DIGEST_ENABLED);
            insert.setToolGate(json);
            TenantHelper.ignore(() -> configMapper.insert(insert));
            return;
        }
        DcsAssistantConfig patch = new DcsAssistantConfig();
        patch.setTenantId(tenantId);
        patch.setToolGate(json);
        TenantHelper.ignore(() -> configMapper.updateById(patch));
    }

    /**
     * 管理端写租户覆盖行（upsert）。部分提交语义（dsflash P2-5 修）：null 字段
     * =保持原值（首插则落 DB 默认）——SQL 侧 EXCLUDED 已被 INSERT COALESCE 污染
     * 分不出"传的默认"与"没传"，故 service 层先读在位行合并非空字段再整行 upsert。
     */
    public void update(DcsAssistantConfig config) {
        if (config.getTenantId() == null || config.getTenantId().isBlank()) {
            throw new ServiceException("tenantId 不能为空");
        }
        // 业务批3-④（dspro P2-2）：联网租户默认列范围校验——0=全租户未开放（DISABLED 语义，
        // 单人覆盖侧 validateLimit 下限是 1，只有租户列能整体关停），负数/超界拒绝
        if (config.getWebsearchLimitPerUser() != null
            && (config.getWebsearchLimitPerUser() < 0 || config.getWebsearchLimitPerUser() > 10000)) {
            throw new ServiceException("每人每日联网次数须在 0~10000 之间（0=关闭联网搜索）", 400);
        }
        // 快刀批 Q6：助手称呼——提交了才动：trim、超长拒绝；空白=显式清除回落平台默认
        // （空串哨兵直通合并，upsert 前归一为 NULL；null=未提交保持原值）
        if (config.getAssistantName() != null) {
            String n = config.getAssistantName().trim();
            if (n.length() > 50) {
                throw new ServiceException("助手称呼最多 50 个字", 400);
            }
            config.setAssistantName(n);
        }
        DcsAssistantConfig existing = raw(config.getTenantId());
        if (existing != null) {
            if (config.getEnabled() == null) {
                config.setEnabled(existing.getEnabled());
            }
            if (config.getDailyRoundLimitPerUser() == null) {
                config.setDailyRoundLimitPerUser(existing.getDailyRoundLimitPerUser());
            }
            if (config.getDailyBudgetAmount() == null) {
                config.setDailyBudgetAmount(existing.getDailyBudgetAmount());
            }
            if (config.getMemoryEnabled() == null) {
                config.setMemoryEnabled(existing.getMemoryEnabled());
            }
            if (config.getCompactionTriggerTokens() == null) {
                config.setCompactionTriggerTokens(existing.getCompactionTriggerTokens());
            }
            // 业务批3-④：租户联网默认列同步合并——漏并则部分提交会把该列回落 DDL 默认 20
            if (config.getWebsearchLimitPerUser() == null) {
                config.setWebsearchLimitPerUser(existing.getWebsearchLimitPerUser());
            }
            // 五需求批 R4b：每日摘要开关同口径合并（漏并=静默关掉租户已开的摘要）
            if (config.getDigestEnabled() == null) {
                config.setDigestEnabled(existing.getDigestEnabled());
            }
            // 快刀批 Q6：称呼合并——null=未提交保持原值（"" 已是显式清除哨兵，不进这支）
            if (config.getAssistantName() == null) {
                config.setAssistantName(existing.getAssistantName());
            }
        }
        if (config.getDigestEnabled() != null && !"0".equals(config.getDigestEnabled())
            && !"1".equals(config.getDigestEnabled())) {
            throw new ServiceException("每日摘要开关只接受 0/1", 400);
        }
        // 快刀批 Q6：空串哨兵归一为 NULL（读侧见 NULL/空白=平台默认；顺带自愈历史脏 "" 行）
        if (config.getAssistantName() != null && config.getAssistantName().isEmpty()) {
            config.setAssistantName(null);
        }
        TenantHelper.ignore(() -> configMapper.upsert(config));
    }

    /**
     * 每日摘要已开启的租户行（五需求批 R4b / Q7-C，08:00 job 扫描用）：
     * enabled=1（总闸关=摘要也不发）且 digest_enabled=1。类级 @InterceptorIgnore，
     * 全表扫不串租户。
     */
    public java.util.List<DcsAssistantConfig> digestEnabledTenants() {
        return TenantHelper.ignore(() -> configMapper.selectList(
            new LambdaQueryWrapper<DcsAssistantConfig>()
                .eq(DcsAssistantConfig::getEnabled, "1")
                .eq(DcsAssistantConfig::getDigestEnabled, "1")));
    }

    /**
     * 管理页统计头（ZCode 对齐 Q4 批C）：全租户四计数 + 本月费用。
     * 只读聚合，读失败不拖垮管理页（前端各数显示 —）。
     */
    public org.dromara.assistant.domain.vo.AssistantAdminStatsVo adminStats(String tenantId) {
        return TenantHelper.ignore(() -> configMapper.selectAdminStats(tenantId));
    }
}
