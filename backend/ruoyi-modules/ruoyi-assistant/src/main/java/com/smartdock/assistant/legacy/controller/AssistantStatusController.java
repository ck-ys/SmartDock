package com.smartdock.assistant.legacy.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.assistant.legacy.domain.DcsAssistantConfig;
import com.smartdock.assistant.infrastructure.AssistantDailyQuota;
import com.smartdock.assistant.legacy.service.AssistantConfigService;
import com.smartdock.assistant.service.AssistantSkillVisibilityService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.assistant.legacy.tools.WebSearchTools;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 助手租户状态/配置端点（批G G1，spec §18）。
 *
 * <p>GET /dcs/assistant/status：悬浮球条件加载的数据源（enabled+modelConfigured+degraded
 * 三态 + 限额/剩余回合——原型 §10.1 统计面板与欢迎引导今日概况的后端数据源之一）。</p>
 */
@Tag(name = "助手租户配置", description = "租户三态感知 + 租户配置读/写")
@RestController
@RequestMapping("/dcs/assistant")
@RequiredArgsConstructor
@Slf4j
public class AssistantStatusController extends BaseController {

    private final AssistantConfigService configService;
    private final AssistantDailyQuota dailyQuota;
    private final com.smartdock.assistant.legacy.service.AssistantConversationService conversationService;
    private final com.smartdock.assistant.service.AssistantUserQuotaService quotaService;
    /** 批3.5·题5：技能使用统计（最近用过端点数据源） */
    private final com.smartdock.assistant.service.AssistantSkillUsageService skillUsageService;
    /** 批 4 题 2 案 1：技能可见性判定统一入口（原 4 处手写复写收成一处） */
    private final AssistantSkillVisibilityService skillVisibility;
    /** 业务批3-④：联网已用次数真值源（此前占位 0） */
    private final com.smartdock.assistant.infrastructure.AssistantWebSearchQuota webSearchQuota;
    /** 业务批7 Q7：今日缓存命中率聚合 */
    private final com.smartdock.service.support.AiBillingService aiBillingService;
    /** 业务反馈批1015b Q14：搜索 key 掩码读（写走参数设置/system config 自带缓存刷新，此处零写） */
    private final org.dromara.common.core.service.ConfigService sysConfigService;
    /** 稳流② Q8②：run 终态分布（进程内累计） */
    private final com.smartdock.assistant.legacy.persist.AssistantRunRecorder runRecorder;
    /** 稳流② Q8②：追问失败分类分布（进程内累计） */
    private final com.smartdock.assistant.legacy.service.AssistantFollowupService followupService;
    /** 批A-1：/config 下发压缩策略派生值（按当前默认模型窗口算，只读展示） */
    private final com.smartdock.assistant.infrastructure.AssistantModelFactory modelFactory;
    /** 批 A Q6（2026-11-14）：/status/context「其他」桶的记忆正文体（租户开记忆才有值） */
    private final com.smartdock.assistant.service.AssistantMemoryService memoryService;

    /**
     * 批3.5·题5（2026-11-03 拍板）：当前登录人最近用过的技能（真值来自 dcs_assistant_skill_usage
     * 按人维度，倒序前 N）——工作台「能做什么」两路取数之一；无记录返回空数组，前端走技能库精选兜底。
     * 与 /skills 同权限（dcs:assistant:use）、同租户口径；统计读侧 fail-open。
     *
     * <p>租户取「动态优先」：超管切到某租户时看的是那个租户的统计，而不是登录租户快照。</p>
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/skills/recent")
    public R<java.util.List<Map<String, Object>>> recentSkills() {
        com.smartdock.assistant.legacy.tools.ToolIdentity identity = com.smartdock.assistant.legacy.tools.ToolIdentity.from(
            org.dromara.common.satoken.utils.LoginHelper.getLoginUser());
        String dynamicTenantId = TenantHelper.getTenantId();
        String tenantId = dynamicTenantId != null && !dynamicTenantId.isBlank()
            ? dynamicTenantId : identity.getTenantId();
        java.util.List<Map<String, Object>> rows = skillUsageService.recentOf(
            tenantId, org.dromara.common.satoken.utils.LoginHelper.getUserId(), 8);
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("skillId", r.get("skillid"));
            row.put("useCount", r.get("usecount"));
            row.put("lastUsedAt", r.get("lastusedat"));
            out.add(row);
        }
        return R.ok(out);
    }

    /**
     * 能力① Q4 轻量（2026-10-20）：用户视角技能目录（perm=use——panorama 是管理端点，
     * 普通用户拉不到）。前端 SKILL_CATALOG 编译期兜底之上动态化：后端加/改技能无需发版。
     * 5b 批题7 甲（2026-10-28）：触发词透出已随自动挂载拆除——什么时候用技能由
     * 技能 description（一句话说明）喂模型判断，前端不再做关键词匹配。
     * B 批题4（2026-10-27）：目录同过工具开关矩阵（skill:<name> 行）——off/提档技能
     * 用户目录不可见，与模型侧可见集（AssistantSkillGateMiddleware）同口径同刻快照。
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/skills")
    public R<java.util.List<Map<String, Object>>> skillCatalog() {
        com.smartdock.assistant.legacy.tools.ToolIdentity identity = com.smartdock.assistant.legacy.tools.ToolIdentity.from(
            org.dromara.common.satoken.utils.LoginHelper.getLoginUser());
        int level = identity.getRoleLevel();
        java.util.Map<String, String> gate = configService.effective(identity.getTenantId()).toolGate();
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (com.smartdock.assistant.legacy.core.SkillAssets.LoadedSkill s : com.smartdock.assistant.legacy.core.SkillAssets.skills()) {
            // 批 4 题 2 案 1：判定统一走 AssistantSkillVisibilityService（此处原为手写复写第 2 份）
            if (!skillVisibility.visible(s.id(), s.minRole(), level, gate)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", s.id());
            row.put("name", s.name());
            row.put("desc", s.description());
            // 三审回修 P2（dsflash）：admin 档如实透出（旧二元映射会把 admin 压成 publisher）
            row.put("minRole", s.minRole() == 0 ? "member" : s.minRole() == 2 ? "admin" : "publisher");
            out.add(row);
        }
        return R.ok(out);
    }

    /**
     * 技能加载失败条数（2026-11-10 Q3=乙）：技能库读到了，但其中有 N 条数据坏掉被跳过。
     *
     * <p>为什么不塞进 {@code /skills} 的返回体：那个端点的契约是「技能数组」，改成对象会让
     * 所有消费方跟着改。这里单开一个端点，前端并行拉，列表结构零改动。</p>
     *
     * <p>权限与目录同（{@code dcs:assistant:use}）：目录看不见的人也不需要知道有几条坏数据。</p>
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/skills/failures")
    public R<Map<String, Object>> skillFailures() {
        com.smartdock.assistant.legacy.tools.ToolIdentity identity = com.smartdock.assistant.legacy.tools.ToolIdentity.from(
            org.dromara.common.satoken.utils.LoginHelper.getLoginUser());
        String dynamicTenantId = TenantHelper.getTenantId();
        String tenantId = dynamicTenantId != null && !dynamicTenantId.isBlank()
            ? dynamicTenantId : identity.getTenantId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("failed", com.smartdock.assistant.legacy.core.DatabaseSkillRepository.failedCount(tenantId));
        return R.ok(out);
    }

    /**
     * 稳流② Q8②（2026-10-20）：助手运行健康（管理页「运行健康」卡数据源）。
     * 两张分布表=进程内累计（重启归零，since=本次启动时刻）——追问哑火/看门狗误判
     * 高危样本（心跳=0 且静默大的 run）从这先看出趋势，需要持久化再落表（观察期后决定）。
     */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/admin/health")
    public R<Map<String, Object>> assistantHealth() {
        String tenantId = LoginHelper.getTenantId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("since", java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()),
            java.time.ZoneId.of("Asia/Shanghai")).toString());
        // glm53 审查 P2-2：两张分布按登录租户过滤（池满丢弃类全局桶随 followup 侧合并下发）
        out.put("runOutcomes", runRecorder.terminalOutcomeSnapshot(tenantId));
        out.put("followupOutcomes", followupService.followupOutcomeSnapshot(tenantId));
        return R.ok(out);
    }

    /** 租户三态感知（perm=dcs:assistant:use；批B B2：带 threadId 时附 contextTokens=
     *  该会话最新 assistant 消息的 total_tokens（末次模型调用 input+cached+output），
     *  前端合并环的上下文占用分子；无会话/未落库/估算缺真值=null=环空态）。
     *  业务批7：billingMode=credits 时附积分三本账快照 + 今日缓存命中率（Q7）。 */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/status")
    public R<Map<String, Object>> status(@RequestParam(required = false) String threadId) {
        String tenantId = LoginHelper.getTenantId();
        Long userId = LoginHelper.getUserId();
        AssistantConfigService.EffectiveConfig cfg = configService.effective(tenantId);
        // 业务批7 Q1：计费感知限额——订阅租户回合=UNLIMITED_ROUNDS、联网=套餐档位
        com.smartdock.assistant.service.AssistantUserQuotaService.BillingAwareLimits limits =
            quotaService.billingAwareLimits(tenantId, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        // 快刀批 Q6：助手称呼（租户可改，读侧两级默认合并后必有值）——前端 nameState 全局态数据源
        out.put("name", cfg.assistantName());
        out.put("enabled", cfg.enabled());
        out.put("modelConfigured", cfg.enabled() && configService.modelConfigured(tenantId));
        out.put("degraded", configService.degraded());
        out.put("billingMode", limits.creditsMode() ? "credits" : "wallet");
        // glm53 P2-3 回修：积分三本账快照字段全删——前端实际数据源=my-summary（有完整
        // windowCap/Used/tankBalance），此处字段零消费纯属死数据；billingMode 保留（前端消费）。
        out.put("dailyRoundLimitPerUser", limits.roundLimit());
        out.put("dailyBudgetAmount", cfg.dailyBudgetAmount());
        // 二站④ AU-53：记忆页按此判断空态（开=拉真数据，关=引导文案）
        out.put("memoryEnabled", "1".equals(cfg.memoryEnabled()));
        long used = dailyQuota.turnsUsed(tenantId, userId);
        out.put("turnsUsedToday", used);
        out.put("remainingTurnsToday", Math.max(0, limits.roundLimit() - used));
        // 业务批3-③(#16)：禁用用户前端显示横幅/提示
        // 业务批3-④(#17)：联网已用/上限真值（Redis 计数 + 三层合成上限），前端状态弹窗联网行
        out.put("quotaDisabled", limits.disabled());
        out.put("websearchUsedToday", webSearchQuota.searchesUsed(tenantId, userId));
        out.put("websearchLimit", limits.websearchLimit());
        out.put("contextTokens", contextTokensOf(tenantId, userId, threadId));
        // 批A 二审回修：本会话生效模型（用户 pin > 助手默认）的自动压缩触发线——
        // 前端水位三值以后端真值为准（自选关/默认行被裁出白名单时本地复算会偏差）
        try {
            com.smartdock.domain.DcsAiConfig eff = modelFactory.resolveConfigWithUserChoice(tenantId, threadId);
            out.put("compactionTriggerTokens", com.smartdock.assistant.legacy.core.AssistantCompactionPolicy
                .deriveTriggerTokens(eff.getContextWindow(), eff.getMaxTokens()));
        } catch (Exception e) {
            // 模型未配置/解析失败：不下发，前端按 20 万兜底本地复算
        }
        // Q9（2026-10-15 拍板A·ZCode 同构）：本会话缓存命中（运行时逐回合累计，只含主链
        // 真实回传；换会话即重置、重启重新累计）。前端状态卡显示口径=本会话；
        // cacheHitRateToday 保留为管理/排错参考（含 Q1 脏数据护栏）。
        // 归属校验（三席审查 P2）：与 contextTokensOf 同款先查会话行，threadId 属本人
        // 才返回聚合——防拿他人/他租户 threadId 读走会话指标；非本人时前端显示「刚开了头」
        if (threadId != null && !threadId.isBlank()) {
            try {
                com.smartdock.assistant.legacy.domain.DcsAssistantConversation conv =
                    conversationService.conversationByThread(tenantId, threadId);
                if (conv != null && userId.equals(conv.getUserId())) {
                    com.smartdock.assistant.legacy.core.AssistantCacheHitHolder.Aggregate agg =
                        com.smartdock.assistant.legacy.core.AssistantCacheHitHolder.aggregate(threadId);
                    out.put("sessionCacheHitRate", agg.ratePct());
                    out.put("sessionCacheHitRequests", agg.requests());
                    // 题2 案1（2026-11-12）：压缩账单——最近一次成功压缩前后 token/时刻＋累计
                    // 次数；前端状态卡按 atMs 是否落在当前回合内决定「本轮压缩」高亮行。
                    // 三审回修：按 conversation_id 查（已索引；thread_id 无索引前缀扫）
                    com.smartdock.assistant.legacy.service.AssistantConversationService.CompactionBill bill =
                        conversationService.compactionBill(tenantId, conv.getId());
                    if (bill.count() > 0) {
                        out.put("compactionBeforeTokens", bill.beforeTokens());
                        out.put("compactionAfterTokens", bill.afterTokens());
                        out.put("compactionAtMs", bill.atMs());
                        out.put("compactionCount", bill.count());
                    }
                }
            } catch (Exception e) {
                // 会话行查询失败按无数据处理，不影响状态接口其余字段
            }
        }
        // 业务批7 Q7：今日缓存命中率（LiteLLM 口径；估算行/侧车调用排除，样本<3 为 null）
        Map<String, Object> cacheHit = aiBillingService.assistantCacheHitToday(tenantId, userId);
        out.put("cacheHitRateToday", cacheHit.get("rate"));
        out.put("cacheHitSamples", cacheHit.get("samples"));
        return R.ok(out);
    }

    /**
     * 批 A Q6（2026-11-14）：本会话上下文构成——七段字符占比（估算，0 段不下发；MCP 段本版不下发）+ 总量/窗口（真值）。
     * 权限与归属校验同 /status（threadId 属本人才聚合）。MCP 段首版不下发：
     * 工具 schema 需活连接 listTools，状态接口不做网络调用（板上「0 桶不出现」口径天然允许）。
     * 2026-11-15 Q6 框架板（Q2=七段+记忆单列 / Q3=只报字符）：拆出「记忆」段、口径改为字符数，
     * pct = 该段字符 ÷ 全部段字符之和；method 名随 VO 下发供脚注展示。
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/status/context")
    public R<com.smartdock.assistant.domain.vo.ContextBreakdownVo> statusContext(@RequestParam String threadId) {
        // 2026-11-15 三审席1 P2-6：与隔壁 /status、会话列表同源取「动态优先」租户——
        // 超管切租户后对话建在动态租户下，用登录快照会恒 0 行（叠加下面的 200 空返回=永远看不到构成）
        String tenantId = org.dromara.common.tenant.helper.TenantHelper.getTenantId();
        Long userId = LoginHelper.getUserId();
        if (threadId == null || threadId.isBlank()) {
            throw new org.dromara.common.core.exception.ServiceException("threadId 不能为空");
        }
        com.smartdock.assistant.legacy.domain.DcsAssistantConversation conv =
            conversationService.conversationByThread(tenantId, threadId);
        if (conv == null || !userId.equals(conv.getUserId())) {
            // 2026-11-15 三审席1/席3 P1：新会话的 threadId 是前端先给的 uuid，会话行要等首次发送才落库；
            // 读接口若抛 403，响应拦截器会对每次悬停弹红条「会话不存在或无权访问」。
            // 与 /status 同款降级：按「0 桶不出现」返回 200 空构成（也不泄露会话是否存在）。
            return R.ok(new com.smartdock.assistant.domain.vo.ContextBreakdownVo(
                null, null, com.smartdock.assistant.support.ContextBreakdownEstimator.METHOD, List.of()));
        }
        boolean memoryOn = "1".equals(configService.effective(tenantId).memoryEnabled());

        // ①系统提示词（除 cheatsheet 外各段，带逐段细分） ②工具提示词（cheatsheet 段）
        // 2026-11-15 Q6 框架板 Q3=2：口径改为「只数字符」——字符是客观量，界面折算并标估算
        long sysPromptChars = 0;
        long toolsPromptChars = 0;
        record SubRaw(String name, long chars) {}
        List<SubRaw> subs = new java.util.ArrayList<>();
        for (com.smartdock.assistant.legacy.core.PromptAssets.PromptSegment seg
            : com.smartdock.assistant.legacy.core.PromptAssets.segments(memoryOn)) {
            // 2026-11-15 三审席1 P2-1：催办纪律段运行口径恒不装配（平台工具无催办类，
            // 见 AssistantAgentFactory 的 sysPrompt(memoryOn,false)）——展示口径会把它算进去，
            // 这里按真实装配口径剔除，否则系统提示词固定偏高。
            if ("discipline-urge".equals(seg.key())) {
                continue;
            }
            long c = com.smartdock.assistant.support.ContextBreakdownEstimator.charsOf(seg.content());
            if ("cheatsheet".equals(seg.key())) {
                toolsPromptChars += c;
            } else {
                sysPromptChars += c;
                subs.add(new SubRaw(seg.title(), c));
            }
        }
        // ③系统工具：只有工具名（ToolSpec 不含描述/参数，真实 schema 要序列化且需活体 toolkit；
        // 状态接口拿不到——低置信度，前端标签如实写「仅名称」，别让人以为这就是完整 schema）
        List<String> toolTexts = new java.util.ArrayList<>();
        for (com.smartdock.assistant.legacy.core.ToolRegistry.ToolSpec spec
            : com.smartdock.assistant.legacy.core.ToolRegistry.SPECS) {
            toolTexts.addAll(spec.toolNames());
        }
        long systemToolsChars = com.smartdock.assistant.support.ContextBreakdownEstimator.charsOf(toolTexts);
        // ④技能（2026-11-15 三审席1 P1-2 修）：与「谁能看见哪个技能」同源——按本角色可见技能的
        // 名字+说明求和（就是提示词目录里每行技能条目的内容），再叠加本会话选中技能的正文。
        // 不再扫 sharedSkillWorkDir：那是框架物化缓存（内置技能不落盘、DB 技能按历史 signature
        // 反复复制且永不删除），量出来既可能恒 0 也可能随进程单调增长，还会混进别的租户的技能。
        long skillsChars = 0;
        try {
            int roleLevel = com.smartdock.assistant.legacy.tools.ToolIdentity.from(LoginHelper.getLoginUser()).getRoleLevel();
            java.util.Map<String, String> skillGate = configService.effective(tenantId).toolGate();
            for (com.smartdock.assistant.legacy.core.SkillAssets.LoadedSkill sk
                : com.smartdock.assistant.legacy.core.SkillAssets.skills(tenantId)) {
                // 批 4 题 2 案 1：与目录/注入同一道闸（判定统一走 AssistantSkillVisibilityService）
                if (!skillVisibility.visible(sk.id(), sk.minRole(), roleLevel, skillGate)) {
                    continue; // 看不见的技能不进构成
                }
                skillsChars += com.smartdock.assistant.support.ContextBreakdownEstimator.charsOf(
                    sk.name() + "\n" + sk.description());
            }
            String selectedSkill = com.smartdock.assistant.legacy.core.AssistantSkillHolder.get(threadId);
            if (selectedSkill != null) {
                skillsChars += com.smartdock.assistant.support.ContextBreakdownEstimator.charsOf(
                    com.smartdock.assistant.legacy.core.SkillAssets.body(selectedSkill, roleLevel, tenantId));
            }
        } catch (Exception e) {
            // 技能视图读失败按 0（桶不出现）；留 warn 防「静默少一段」没人查
            log.warn("[status/context] 技能字符统计失败: {}", e.getMessage());
        }
        // ⑤消息：本会话正文（最近 500 条防全表；含工具步骤——工具结果不落库，见实体注释）。
        // 先取一次，后面的「记忆」按最后一条用户提问打分也要用同一份尾部。
        List<com.smartdock.assistant.legacy.domain.DcsAssistantMessage> tail = List.of();
        try {
            tail = conversationService.listMessagesTail(tenantId, userId, conv.getId(), 500);
        } catch (Exception e) {
            log.warn("[status/context] 消息读取失败: {}", e.getMessage()); // 桶不出现，但留痕
        }
        long messagesChars = com.smartdock.assistant.support.ContextBreakdownEstimator.charsOf(
            tail.stream().map(m -> (m.getContent() == null ? "" : m.getContent())
                + (m.getToolSteps() == null ? "" : m.getToolSteps())).toList());
        // ⑥记忆（Q4=1 单列）：不是「记忆库总量」，而是本轮按最后一条用户提问打分后**真正会注入**的那份
        // （2026-11-15 三审席1/席3 P1）：问题式打分全不命中时注入 0 字节，面板也必须显示 0 桶不出现。
        long memoryChars = 0;
        if (memoryOn) {
            try {
                String question = null;
                for (int i = tail.size() - 1; i >= 0; i--) {
                    com.smartdock.assistant.legacy.domain.DcsAssistantMessage m = tail.get(i);
                    if (m != null && "user".equals(m.getRole()) && m.getContent() != null
                        && !m.getContent().isBlank()) {
                        question = m.getContent();
                        break;
                    }
                }
                // 只读版：不回写 last_used_at（不污染 90 天归档判据）；带上注入前缀同一份文本
                com.smartdock.assistant.domain.model.DigestResult mem =
                    memoryService.digestReadOnly(tenantId, userId, question);
                if (mem != null) {
                    memoryChars = com.smartdock.assistant.support.ContextBreakdownEstimator.charsOf(
                        com.smartdock.assistant.legacy.core.MemoryInjectModelDecorator.MEMORY_PROMPT_PREFIX + mem.text());
                }
            } catch (Exception e) {
                log.warn("[status/context] 记忆读取失败: {}", e.getMessage()); // 桶不出现，但留痕
            }
        }
        // ⑦其他 = 日期注入段（记忆已单列，不再混在「其他」里）
        long otherChars = com.smartdock.assistant.support.ContextBreakdownEstimator.charsOf(
            com.smartdock.assistant.legacy.core.DateInjectModelDecorator.dateSegmentText());
        // 汇总：降序 + pct（=该桶字符数 ÷ 全部桶字符数之和，0 桶剔除；系统提示词的 sub 按 sysPromptChars 内占比换算）
        record Raw(String key, String label, long chars, boolean lowConfidence, List<SubRaw> subs) {}
        final long sysPromptTotal = sysPromptChars;
        List<Raw> raws = new java.util.ArrayList<>();
        if (sysPromptChars > 0) {
            raws.add(new Raw("system_prompt", "系统提示词", sysPromptChars, false, subs));
        }
        if (memoryChars > 0) {
            raws.add(new Raw("memory", "记忆", memoryChars, false, null));
        }
        if (toolsPromptChars > 0) {
            raws.add(new Raw("tools_prompt", "工具提示词", toolsPromptChars, false, null));
        }
        if (systemToolsChars > 0) {
            raws.add(new Raw("system_tools", "系统工具（仅名称）", systemToolsChars, true, null));
        }
        if (skillsChars > 0) {
            raws.add(new Raw("skills", "技能", skillsChars, false, null));
        }
        if (otherChars > 0) {
            raws.add(new Raw("other", "其他", otherChars, false, null));
        }
        if (messagesChars > 0) {
            raws.add(new Raw("messages", "消息（近 500 条）", messagesChars, true, null));
        }
        long sum = raws.stream().mapToLong(Raw::chars).sum();
        raws.sort(java.util.Comparator.comparingLong(Raw::chars).reversed());
        List<com.smartdock.assistant.domain.vo.ContextBreakdownVo.Bucket> buckets = new java.util.ArrayList<>();
        for (Raw r : raws) {
            List<com.smartdock.assistant.domain.vo.ContextBreakdownVo.Sub> subOut = null;
            if (r.subs() != null && sysPromptTotal > 0) {
                subOut = r.subs().stream()
                    .map(x -> new com.smartdock.assistant.domain.vo.ContextBreakdownVo.Sub(x.name(),
                        com.smartdock.assistant.support.ContextBreakdownEstimator.pctOf(x.chars(), sysPromptTotal)))
                    .toList();
            }
            double pct = com.smartdock.assistant.support.ContextBreakdownEstimator.pctOf(r.chars(), sum);
            if (pct <= 0) {
                continue; // 2026-11-15 三审席3 P2-8：占比舍入到 0.0 的桶不下发，避免「约 0%」被当成坏了
            }
            buckets.add(new com.smartdock.assistant.domain.vo.ContextBreakdownVo.Bucket(
                r.key(), r.label(), r.chars(), pct, r.lowConfidence(), subOut));
        }
        Long windowTokens = null;
        try {
            windowTokens = (long) modelFactory.resolveConfigWithUserChoice(tenantId, threadId)
                .getContextWindow();
        } catch (Exception ignored) {
            // 模型未配置：窗口不下发，前端按 20 万兜底
        }
        return R.ok(new com.smartdock.assistant.domain.vo.ContextBreakdownVo(
            contextTokensOf(tenantId, userId, threadId), windowTokens,
            com.smartdock.assistant.support.ContextBreakdownEstimator.METHOD, buckets));
    }

    /** 批B B2：threadId → 本会话最新 assistant 消息末次调用 tokens（无会话/无值返回 null）。
     *  B1 修：取「最新 tokens 非空行」——truncated/中断行恒空，取最新行会被空值遮蔽 */
    private Long contextTokensOf(String tenantId, Long userId, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        try {
            com.smartdock.assistant.legacy.domain.DcsAssistantConversation conv =
                conversationService.conversationByThread(tenantId, threadId);
            if (conv == null) {
                return null;
            }
            return conversationService.latestContextTokens(tenantId, userId, conv.getId());
        } catch (Exception e) {
            return null;
        }
    }

    /** 管理页统计头（ZCode 对齐 Q4 批C）：全租户会话/消息/动作/反馈计数 + 本月费用（perm=config 同源） */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/admin/stats")
    public R<com.smartdock.assistant.legacy.domain.vo.AssistantAdminStatsVo> adminStats() {
        return R.ok(configService.adminStats(LoginHelper.getTenantId()));
    }

    /** 租户配置读（perm=dcs:assistant:config；effective=两级默认合并结果，row=覆盖行原文；
     *  compaction=批A-1 压缩策略派生值——触发线按当前默认模型窗口自动算，管理页只读展示） */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        String tenantId = LoginHelper.getTenantId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("effective", configService.effective(tenantId));
        out.put("row", configService.raw(tenantId));
        Map<String, Object> compaction = new LinkedHashMap<>();
        Integer window = null;
        Integer maxOutput = null;
        try {
            com.smartdock.domain.DcsAiConfig def = modelFactory.resolveConfig(tenantId);
            window = def.getContextWindow();
            maxOutput = def.getMaxTokens();
        } catch (Exception e) {
            // 未配置模型/解析失败：派生值按兜底窗口给出，derived=false 供前端提示
            org.slf4j.LoggerFactory.getLogger(AssistantStatusController.class)
                .debug("[Compaction] 默认模型解析失败, 压缩策略按兜底窗口展示 tenant={}: {}", tenantId, e.getMessage());
        }
        // 二审 P2：derived=true 必须意味着「按模型表实算」——窗口缺失时走了 20 万兜底，
        // 前端按 false 提示「未解析到模型」，不能谎报实算
        boolean derived = window != null && window > 0;
        compaction.put("derived", derived);
        compaction.put("contextWindow",
            com.smartdock.assistant.legacy.core.AssistantCompactionPolicy.effectiveContextWindow(window));
        compaction.put("triggerTokens",
            com.smartdock.assistant.legacy.core.AssistantCompactionPolicy.deriveTriggerTokens(window, maxOutput));
        compaction.put("keepMessages", com.smartdock.assistant.legacy.core.AssistantCompactionPolicy.KEEP_MESSAGES);
        out.put("compaction", compaction);
        return R.ok(out);
    }

    /** 租户配置写（upsert；超管写任意租户须走跨租户端点=P2 登记项）；铁律#21：PUT→POST /config/edit（2026-10-16 批17 Q4，前端已同步） */
    @SaCheckPermission("dcs:assistant:config")
    @Log(title = "AI助手-租户配置", businessType = BusinessType.UPDATE)
    @PostMapping("/config/edit")
    public R<Void> updateConfig(@RequestBody DcsAssistantConfig body) {
        body.setTenantId(LoginHelper.getTenantId());
        configService.update(body);
        return R.ok();
    }

    /**
     * 联网搜索配置读（perm=config，管理页卡片数据源）。
     *
     * <p>N3 拍板（2026-10-23）：只认本租户自己的 key，平台兜底已删。安全口径不变：密钥只出
     * 掩码（前 6 后 4），明文永不出后端。</p>
     */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/admin/websearch")
    public R<Map<String, Object>> websearchInfo() {
        // 超管切租户后读面也要看被切换到的租户（TenantHelper 动态优先）
        String tenantId = TenantHelper.getTenantId();
        String key = callInTenant(tenantId, () -> sysConfigService.getConfigValue(WebSearchTools.CONFIG_KEY));
        String trim = key == null ? "" : key.trim();
        boolean usable = WebSearchTools.isUsableKey(trim);
        // 短 key 前后段会重叠导致掩码形同虚设：不足 16 位只回 ****
        String mask = usable
            ? (trim.length() < 16 ? "****" : trim.substring(0, 6) + "****" + trim.substring(trim.length() - 4))
            : "";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", usable);
        out.put("maskedKey", mask);
        out.put("placeholderHint", trim.isEmpty() ? "本租户未配置" : usable ? "" : "已配置但为占位/停用值（off/disabled 或非 sk- 前缀）");
        out.put("configKey", WebSearchTools.CONFIG_KEY);
        return R.ok(out);
    }

    /**
     * 搜索连通测试（perm=config）。body.key 可选——带值=先验新钥匙再替换（推荐流程），
     * 不带=用本租户当前生效 key（与真实调用同一解析，平台兜底已删）。
     * 打一次 count=1 的真请求量延迟；任何异常返回 ok=false + 人话原因，不抛 500。
     */
    @SaCheckPermission("dcs:assistant:config")
    @PostMapping("/admin/websearch/test")
    public R<Map<String, Object>> websearchTest(@RequestBody(required = false) Map<String, String> body) {
        String tenantId = TenantHelper.getTenantId();
        String candidate = body == null ? null : body.get("key");
        String key = candidate != null && !candidate.isBlank() ? candidate.trim() : null;
        if (key == null) {
            String saved = callInTenant(tenantId,
                () -> sysConfigService.getConfigValue(WebSearchTools.CONFIG_KEY));
            key = saved == null ? "" : saved.trim();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (!WebSearchTools.isUsableKey(key)) {
            out.put("ok", false);
            out.put("message", "本租户还没有可用的搜索密钥，请先配置");
            return R.ok(out);
        }
        long start = System.currentTimeMillis();
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5)).build();
            String reqBody = "{\"query\":\"connectivity test\",\"count\":1}";
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://api.bocha.cn/v1/web-search"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(reqBody)).build();
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            if (resp.statusCode() == 200) {
                out.put("ok", true);
                out.put("latencyMs", latency);
                out.put("message", "连通正常（" + latency + " ms）");
            } else {
                out.put("ok", false);
                out.put("message", switch (resp.statusCode()) {
                    case 401 -> "密钥无效（401）：请核对博查后台的 key";
                    case 403 -> "无权限（403）：key 可能已停用或套餐到期";
                    case 429 -> "触发限流（429）：稍后再试";
                    default -> "上游返回 " + resp.statusCode();
                });
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "请求失败：" + e.getMessage());
        }
        return R.ok(out);
    }

    /**
     * 在指定租户上下文内执行并恢复原动态租户。
     *
     * <p>不能直接套 {@link TenantHelper#dynamic}：其 finally 的 clearDynamic 在登录线程上会
     * 连超管「切换单位」的持久态一起删（仓内 TenantScopes 同款口径）。</p>
     */
    private <T> T callInTenant(String tenantId, java.util.function.Supplier<T> body) {
        if (org.dromara.common.core.utils.StringUtils.isBlank(tenantId)) {
            return body.get();
        }
        String prevDynamic = TenantHelper.getDynamic();
        try {
            return TenantHelper.dynamic(tenantId, body);
        } finally {
            if (org.dromara.common.core.utils.StringUtils.isNotBlank(prevDynamic)) {
                TenantHelper.setDynamic(prevDynamic, true);
            }
        }
    }
}