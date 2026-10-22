package org.dromara.assistant.core;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.limiter.AssistantAdmissionGate;
import org.dromara.assistant.limiter.AssistantDailyQuota;
import org.dromara.assistant.security.AssistantInputGuard;
import org.dromara.assistant.service.AssistantConfigService;
import org.dromara.assistant.service.AssistantConversationService;
import org.dromara.assistant.service.AssistantModelSettingService;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * AGUI 官方身份钩子（批A Task A2；批D D2 准入闸；批G 五环链 2026-10-06）。
 *
 * <p>每个 /agui/run 请求进入框架前由此解析身份并过全链前置闸（费用发生前的
 * 全部拦截点，任何一环拒绝都不产生模型成本）：</p>
 * <ol>
 *   <li>身份：Authorization Bearer → Sa-Token（拿不到=401，绝不 null→ANON 共享会话）；</li>
 *   <li>权限补审 P1-2（2026-10-12 用户点名补审）：角色须持有 dcs:assistant:use——
 *       与 REST 面同口径，未开通助手的角色（发布人/领导/协调等）持合法 token 也不再放行；</li>
 *   <li>G4 输入体积闸（16K，本地零成本最先跑）；</li>
 *   <li>G1 租户开关闸（未开通=403 人话；PG 不可读=503 降级拒绝）；</li>
 *   <li>G5 日限额（回合 INCR+比限 / 租户日预算=账本真值直查）——后续环节异常补偿回退；</li>
 *   <li>D2 准入闸（Redis 滑窗活跃双限）。</li>
 * </ol>
 * <p>全环过后落 G2 持久化（thread 归属首绑 + run 登记 + user 消息）：归属冲突=403
 * 并补偿回合；PG 闪断=降级继续对话（历史缺失可容忍，§6.1），绝不让记录故障拖死对话。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantRuntimeContextResolver implements AguiRuntimeContextResolver {

    private final AssistantAdmissionGate admissionGate;
    private final AssistantConfigService configService;
    private final AssistantInputGuard inputGuard;
    private final AssistantDailyQuota dailyQuota;
    private final AssistantConversationService conversationService;
    private final org.dromara.assistant.service.AssistantUserQuotaService quotaService;
    /** ZCode 对齐 Q1（批A 2026-10-10）：首条消息后异步起标题的侧车 */
    private final org.dromara.assistant.service.AssistantTitleService titleService;
    /** 批7 Q12-④: 模型切换失效缓存 agent（agent 按 (user,thread) 缓存且模型建时固化） */
    private final io.agentscope.spring.boot.agui.common.ThreadSessionManager threadSessionManager;
    /** 业务批4 Q1-A：白名单范围指纹比对——管理员改 switch_scope 后缓存会话下一请求即失效重建 */
    private final org.dromara.assistant.service.AssistantModelSettingService modelSettingService;
    private final org.dromara.assistant.service.AssistantConfigService assistantConfigService;
    /** 批A-1：压缩触发线指纹需要按当前模型窗口派生（与建侧同源解析） */
    private final org.dromara.assistant.factory.AssistantModelFactory modelFactory;

    @Override
    public RuntimeContext resolve(AguiRuntimeContextRequest<?> request) {
        String token = extractBearerToken(request);
        if (token == null || token.isBlank()) {
            throw new ServiceException("未登录，无法使用 AI 助手", 401);
        }
        LoginUser user = LoginHelper.getLoginUser(token);
        if (user == null) {
            throw new ServiceException("登录状态无效或已过期，请重新登录", 401);
        }
        // 权限补审 P1-2（2026-10-12）：/agui/run 此前只验登录不验助手权限——未发 use 的角色
        // （发布人/领导/协调等）持合法 token 也能跑模型烧租户钱包。与 REST 面（11920/11921
        // 菜单串）同口径。注意：resolver 跑在框架流线程，Sa-Token 请求上下文已解绑
        // （E2E 实证 StpUtil 报「上下文尚未初始化」）——用 LoginUser 登录快照里的权限集判定；
        // 超管快照含 *:*:* 通配（SysPermissionServiceImpl 装载），RuoYi 体系无部分通配形态。
        // 拒绝走框架错误帧通道（同各闸），不产生模型成本
        java.util.Set<String> menuPerms = user.getMenuPermission();
        boolean assistantGranted = LoginHelper.isSuperAdmin(user.getUserId())
            || (menuPerms != null && (menuPerms.contains("*:*:*") || menuPerms.contains("dcs:assistant:use")));
        if (!assistantGranted) {
            log.warn("[AssistantGate] 角色未开通 AI 助手, 拒绝: tenant={} userId={}", user.getTenantId(), user.getUserId());
            throw new ServiceException("您所在的角色未开通 AI 助手，如需使用请联系管理员开通", 403);
        }
        // 业务反馈批1015b Q10b（写路径口径对齐）：超管切租户后，助手读面（会话列表等 REST）
        // 已改 TenantHelper.getTenantId()（动态优先）；本 resolver 跑在框架流线程，Sa-Token
        // 请求上下文已解绑（TenantHelper.getDynamic 的 SaHolder/StpUtil 不可用），但动态租户
        // 的 Redis 键按 userId 可直读（与 setDynamic(tenantId,true) 同键）。普通用户无此键
        // → 回退登录快照租户，行为不变；读不到 Redis（异常）也回退，不让租户解析拖死对话
        String tenantId = resolveTenantId(user);
        Long userId = user.getUserId();
        RunAgentInput input = request.getInput();

        // ①G4 体积闸（本地校验最先跑，零外部依赖）
        inputGuard.validate(input);

        // ②G1 租户开关闸
        configService.assertOpen(tenantId);

        // ②b 用户禁用闸（业务批3-③ #16）：管理员可单独暂停某用户（覆盖行 disabled='1'），
        // 在回合计数之前拒绝——被拒请求不消耗额度；话术人话化指明联系管理员
        quotaService.assertEnabled(tenantId, userId);

        // ③G5 日限额（回合计数在此发生后，后续任一环拒绝都要补偿——被拒的回合不算数）；
        // 限额取单人生效值（覆盖行 > 租户默认，业务批3-③）
        // 业务批7 Q1：计费感知限额——积分订阅租户回合不限次（套餐积分即消耗闸）+日预算闸
        // 跳过（积分模式钱包不动账，SUM 恒不增长=空转）；回合急停(配0)仍拦截不被套餐绕过
        AssistantConfigService.EffectiveConfig cfg = configService.effective(tenantId);
        org.dromara.assistant.service.AssistantUserQuotaService.BillingAwareLimits limits =
            quotaService.billingAwareLimits(tenantId, userId);
        // glm53 终审 P1-1 回修：订阅租户的回合急停(配0)必须仍走 tryTurn——credits 分支
        // 跳过 tryTurn 会连"limit<=0 抛 503 急停"一起跳过，刹车失效；UNLIMITED_ROUNDS
        // 在 tryTurn 内天然放行，非订阅路径行为不变
        if (!limits.creditsMode() || limits.roundLimit() <= 0) {
            dailyQuota.tryTurn(tenantId, userId, limits.roundLimit());
        }
        try {
            if (!limits.creditsMode()) {
                // ④G5 租户日预算（账本真值直查）
                dailyQuota.checkBudget(tenantId, cfg.dailyBudgetAmount());
            }
            // ⑤D2 准入闸（在册成员续期, 新成员判限才入册）
            admissionGate.admit(tenantId, userId);
            // ⑥G2 持久化：归属首绑 + run 登记 + user 消息（X-08 明文例外通道）
            persistUserSide(tenantId, userId, input);
        } catch (RuntimeException e) {
            // 补偿只退本请求扣过的回合：credits 模式 tryTurn 已跳过，退了会误退
            // 当天早前非订阅期扣的计数（Lua 下限保护防负数，但计数方向偏松）
            if (!limits.creditsMode()) {
                dailyQuota.refundTurn(tenantId, userId);
            }
            throw e;
        }

        // 身份桥：同线程稍后的 registry 工厂 supplier（无 Sa-Token 上下文）从这里取身份；
        // threadId 一并桥给附件工具（按会话取附件，AGUI 每轮必带）；runId 桥给工厂
        // 建会话失败时的同步终局化（Q4/ISSUE-007①）
        AssistantIdentityHolder.set(user, input == null ? null : input.getThreadId(),
            input == null ? null : input.getRunId());

        // 批8-3 回修（glm53 P1-3/dspro P2-3）：思考档/模型选择写入与缓存失效挪到全部门禁+
        // thread 归属校验之后——被拒请求（403/413/429/异主）不再污染会话级暂存，同租户伪造
        // 他人 threadId 也无法注入模型选择或打掉他人缓存会话。thinkTier 原位置同款缺陷一并后移。
        String aguiThreadId = input == null ? null : input.getThreadId();
        AssistantThinkingTierHolder.set(aguiThreadId, input == null ? null : input.getForwardedProp("thinkTier"));
        AssistantModelChoiceHolder.set(aguiThreadId, input == null ? null : input.getForwardedProp("modelConfigId"));
        // 业务批3-④ #17：联网开关逐请求上行（默认关=不传/非真值），工具注册闸读它裁 web_search
        AssistantWebSearchHolder.set(aguiThreadId, input == null ? null : input.getForwardedProp("webSearchEnabled"));
        // 五需求批 R1：技能选择逐请求上行（未选=不传）。与 thinkTier/model 不同，装饰器每次
        // 模型调用现读 holder——换选/摘掉下一轮即生效，无需失效缓存 agent
        AssistantSkillHolder.set(aguiThreadId, input == null ? null : input.getForwardedProp("skillId"));
        // 批8-3 回修（dspro P1-1，javap 字节码实证）：agent 会话键=(复合用户"t:租户:用户", threadId)，
        // removeSession 单参版本按 ("__anon__",threadId) 查键恒 miss（原实现=死代码）——必须双参直指；
        // 「跟随默认」(声明 null)也参与差异判定（Holder.needsRebuild 已同步修），未命中返回 false 记 debug。
        // 批④：联网开关翻转同样失效缓存（工具集建时固化，不失效=关掉后模型仍带 web_search schema）
        // 业务批4 Q1-A：自选范围白名单变化同样失效（指纹比对，管理员保存后该租户所有会话
        // 下一请求即按新范围重建——不再吃满 60 分钟会话超时才生效）
        // 业务批5 Q4-A：指纹扩「助手默认指向」段（defaultAwareFingerprint 与 AgentFactory 建
        // 侧同源）——管理员换助手默认后缓存会话下一条消息即用新模型，不吃会话超时延迟
        // 业务批5 回修：组合读一次同帧取白名单+默认指向（原先两次独立 selectOne，热路径翻倍且可半新半旧）
        AssistantModelSettingService.ScopeAndDefault sd = modelSettingService.scopeAndDefault(tenantId);
        boolean rebuildNeeded = AssistantModelChoiceHolder.needsRebuild(aguiThreadId)
            || AssistantWebSearchHolder.needsRebuild(aguiThreadId)
            || AssistantModelChoiceHolder.needsScopeRebuild(aguiThreadId,
                AssistantModelChoiceHolder.defaultAwareFingerprint(sd.scopeIds(), sd.defaultConfigId(), sd.usableSignature()))
            // G1 遗留#3：工具闸/记忆/压缩配置变化同样失效重建（与 Factory 建侧同源指纹）。
            // 三审回修（dspro P1-5）：比对侧必须用 Factory 落标同款租户源（user 登录快照）——
            // 原用 resolveTenantId 的动态租户，超管切租户且两租户配置有差时每请求恒失配=空转重建
            || AssistantModelChoiceHolder.needsConfigRebuild(aguiThreadId,
                AssistantModelChoiceHolder.assemblyFingerprint(assistantConfigService.effective(user.getTenantId()),
                    derivedTriggerTokens(user.getTenantId(), aguiThreadId)));
        if (aguiThreadId != null && rebuildNeeded) {
            boolean removed = threadSessionManager.removeSession("t:" + tenantId + ":" + userId, aguiThreadId);
            if (removed) {
                log.info("[AgentRebuild] 模型选择/联网开关/自选范围变化, 失效缓存 agent 重建 thread={}", aguiThreadId);
            } else {
                log.debug("[AgentRebuild] 声明变化但无可失效缓存(首次构建/已过期) thread={}", aguiThreadId);
            }
        }
        return RuntimeContext.builder()
            .userId("t:" + tenantId + ":" + userId)
            .build();
    }

    /** 与 AssistantAgentFactory 建侧同源的压缩触发线（解析失败按兜底窗口，不阻断会话） */
    private int derivedTriggerTokens(String tenantId, String aguiThreadId) {
        try {
            com.smartdock.domain.DcsAiConfig cfg = modelFactory.resolveConfigWithUserChoice(tenantId, aguiThreadId);
            return AssistantCompactionPolicy.deriveTriggerTokens(cfg.getContextWindow(), cfg.getMaxTokens());
        } catch (Exception e) {
            return AssistantCompactionPolicy.deriveTriggerTokens(null, null);
        }
    }

    /**
     * user 侧持久化：thread 首绑（异主 403）→ run 登记制 → user 消息落库。
     * PG 故障=降级继续（DataAccessException 吞掉记日志）；归属冲突=403 上抛（S-12）。
     */
    private void persistUserSide(String tenantId, Long userId, RunAgentInput input) {
        if (input == null || input.getThreadId() == null) {
            return;
        }
        try {
            String threadId = input.getThreadId();
            // ZCode 对齐 Q1：会话行不存在=首条消息，落库后触发标题侧车（AGUI 每轮重传全量
            // 历史，必须用「落库前查行」判首条，否则每轮都会重复起标题）
            boolean firstRound = conversationService.conversationByThread(tenantId, threadId) == null;
            String title = firstUserText(input);
            // 当轮口径（glm53 P1-2/dsflash 同源修）：AGUI 每轮重传全量历史，
            // 只落最后一条 user 消息（本轮新增）——逐条落库会在批E 客户端上线后
            // 每轮重复入库污染会话表；与 AssistantInputGuard 同一口径。
            // 三步下沉 persistUserRound 单事务（dspro P2-8：防 PG 闪断留空壳会话）
            AguiMessage current = AssistantInputGuard.lastUserMessage(input);
            // glm53 P2-3 回修：按码点截断（substring 会劈开边界代理对，与 shouldGenerate 的码点口径对齐）
            String initialTitle = title == null ? "新会话"
                : title.substring(0, title.offsetByCodePoints(0, Math.min(30, title.codePointCount(0, title.length()))));
            conversationService.persistUserRound(tenantId, userId, threadId, input.getRunId(),
                initialTitle, current == null ? null : current.getTextContent());
            // 首条且够长：异步侧车把截断标题总结成短标题（CAS 防覆盖用户重命名）
            if (firstRound && title != null) {
                titleService.tryStart(tenantId, userId, threadId, title, initialTitle);
            }
        } catch (ServiceException se) {
            throw se;
        } catch (DataAccessException e) {
            log.warn("[AssistantPersist] PG 闪断, user 侧降级跳过(对话继续) tenant={}: {}", tenantId, e.getMessage());
        }
    }

    private String firstUserText(RunAgentInput input) {
        if (input.getMessages() == null) {
            return null;
        }
        for (AguiMessage m : input.getMessages()) {
            if (m != null && m.isUserMessage() && m.getTextContent() != null && !m.getTextContent().isBlank()) {
                return m.getTextContent();
            }
        }
        return null;
    }

    /**
     * 兼容 Bearer 前缀与裸 token 两种 Authorization 形态（AGUI 前端标准是 Bearer）。
     */
    private String extractBearerToken(AguiRuntimeContextRequest<?> request) {
        String header = request.firstHeader("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        return header.startsWith("Bearer ") ? header.substring(7).trim() : header.trim();
    }

    /** Q10b（业务反馈批1015b）：流线程内解析"当前看的租户"——Redis 动态租户键（超管切换产生，
     * 与 TenantHelper.setDynamic(tenantId,true) 同键）优先，否则回登录快照租户；读键异常也回退，
     * 不让租户解析拖死对话。普通用户无该键，行为不变 */
    private static String resolveTenantId(LoginUser user) {
        try {
            String dynamic = org.dromara.common.redis.utils.RedisUtils.getCacheObject(
                org.dromara.common.core.constant.GlobalConstants.GLOBAL_REDIS_KEY
                    + "dynamicTenant:" + user.getUserId());
            if (dynamic != null && !dynamic.isBlank()) {
                return dynamic;
            }
        } catch (Exception e) {
            log.debug("[AssistantGate] 动态租户键读取失败，回退登录租户 userId={}", user.getUserId());
        }
        return user.getTenantId();
    }
}
