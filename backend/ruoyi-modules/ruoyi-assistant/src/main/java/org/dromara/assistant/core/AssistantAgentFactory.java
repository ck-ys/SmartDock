package org.dromara.assistant.core;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.factory.AssistantModelFactory;
import io.agentscope.core.permission.PermissionContextState;
import org.dromara.assistant.tools.ToolIdentity;
import org.dromara.common.core.service.ConfigService;
import org.dromara.assistant.billing.BillingModelDecorator;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.domain.model.LoginUser;
// 批3 微核心化: 下列 com.smartdock.* 计费域类已归位 ruoyi-ai-billing 模块
// (包名未迁), 旧 dcs 业务模块删除后依赖仍闭合
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.service.IDcsAiModelPriceService;
import com.smartdock.service.support.AiBillingService;
import com.smartdock.service.support.TenantWalletService;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 按登录用户构建助手 agent 的工厂（批A A2 起，批B B1 挂工具面）。
 *
 * <p>注册形态为 registerFactory 惰性工厂：每次新会话（userId+threadId 首次出现）
 * 在请求线程调 {@link #createFromLogin()}，按当前登录人的租户解析 dcs_ai_config
 * 启用行建模型——多租户各自模型配置、互不串门。</p>
 *
 * <p>agent 必须命名（坑66：eviction/清洗路径调 getName()，不命名=NPE 静默失败）；
 * 工厂每次调用返回新实例，禁共享单例（会话隔离前提）；工具集随会话 new
 * （身份进构造器，禁 static holder）。</p>
 *
 * <p>批3 微核心化（2026-10-18）：业务工具面全走 {@link ProviderToolRegistry}
 * 的 BizToolProvider SPI，本工厂不再持任何旧 dcs 工具服务——助手对业务模块
 * 零编译依赖，任何业务缺席照常启动。保留的强注入只剩计费链（ai-billing）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantAgentFactory {

    /** AGUI 端点钉死的 agent 标识（POST /agui/run/{AGENT_ID}）。 */
    public static final String AGENT_ID = "dcs-assistant";

    // Q8（20261011 拍板A，批2）：系统提示词迁至 PromptAssets 分段 md 管理
    // （role/discipline/layout/cheatsheet/memory，三级兜底+零行为护栏单测），
    // 迁移前旧常量 SYS_PROMPT/MEMORY_PROMPT 的冻结对照见 PromptAssetsZeroDiffUnitTest。

    private final AssistantModelFactory modelFactory;
    private final AgentStateStore stateStore;
    /** 能力目录(批2 双层档位: 装配期解析思考能力注入档位装饰器) */
    private final org.dromara.assistant.service.CapabilityService capabilityService;
    /** 计费落账(批D D1: 装饰器写 usage+扣钱包, 事务化) */
    private final AiBillingService aiBillingService;
    /** 五需求批 R5：积分套餐服务(订阅租户对话走积分池, 未订阅零变化) */
    private final com.smartdock.service.IDcsAiCreditService creditService;
    /** AI 缺口批 Q8：厂商钥匙池（主对话失败冷却/成功清冷却上报） */
    private final com.smartdock.service.support.ProviderKeyPool providerKeyPool;
    /** 钱包余额查询(批D D1: 装饰器前置余额闸) */
    private final TenantWalletService tenantWalletService;
    /** 模型价格查询(批D 回修: 装饰器按当前价估算回合门槛) */
    private final IDcsAiModelPriceService modelPriceService;
    /** 回合成本累加器(批E E4: 装饰器逐笔累计, enricher RUN_FINISHED 发 cost_meta) */
    private final org.dromara.assistant.billing.AssistantCostLedger costLedger;
    /** 计划卡信号仓(二站③ AU-51: submit_plan 落计划, enricher 转 plan CUSTOM 帧) */
    private final AssistantPlanSignal planSignal;
    /** PII 确定性过滤器(批G G4: 出网消息脱敏装饰) */
    private final org.dromara.assistant.security.AssistantPiiFilter piiFilter;
    /** 系统参数读取(博查搜索 key 走 sys_config, 2026-10-06 用户拍板) */
    private final ConfigService configService;
    /** 会话附件(附件批 ATTACH-2: read_attachment 工具数据源) */
    private final org.dromara.assistant.service.AssistantAttachmentService attachmentService;
    /** 长期记忆(二站④ AU-53: memoryEnabled 开关+save_memory 工具+逐轮注入装饰器) */
    private final org.dromara.assistant.service.AssistantConfigService assistantConfigService;
    private final org.dromara.assistant.service.AssistantMemoryService memoryService;
    /** 业务批4 Q1-A：建 agent 时回写自选范围指纹基准（resolver 比对判白名单变化失效重建） */
    private final org.dromara.assistant.service.AssistantModelSettingService modelSettingService;
    /** TODO 清单信号仓(ZCode 对齐 Q2, 批A: todo_write 工具实时推送) */
    private final AssistantTodoSignal todoSignal;
    /** 会话服务(ZCode 对齐 Q2, 批A: TodoTools 清单落库) */
    private final org.dromara.assistant.service.AssistantConversationService conversationService;
    /** 联网搜索日配额(业务批3-④: web_search 执行闸+失败补偿) */
    private final org.dromara.assistant.limiter.AssistantWebSearchQuota webSearchQuota;
    /** run 终局化(Q4/ISSUE-007①: 建会话失败时同步 FAILED+退回合, 免 15min 假生成) */
    private final org.dromara.assistant.persist.AssistantRunRecorder runRecorder;
    /** 用户额度服务(业务批3-④: 联网限额三层合成唯一出口) */
    private final org.dromara.assistant.service.AssistantUserQuotaService quotaService;
    /** 批2 SPI 化: BizToolProvider 注册器(零 provider 时为空表, 助手无业务模块也能起) */
    private final ProviderToolRegistry providerToolRegistry;

    private final org.dromara.assistant.service.McpPilotService mcpPilotService;
    /** 压缩工作区(批17 Q2: 中间件卸载归档用, AssistantWorkspaceConfig 装配) */
    private final io.agentscope.harness.agent.workspace.WorkspaceManager workspaceManager;

    /**
     * 按显式登录用户建 agent（单测/内部调用入口，不依赖请求线程上下文）。
     *
     * <p>A3 挂 JDBC 状态存储（server-side-memory 会话记忆落 PG）；
     * 批D D1 起模型套计费装饰器（每次调用记 usage+扣钱包, 余额不足前置拦截）；
     * 批G G4 起计费内层再套 PII 出网装饰器（Billing 包 Pii 包原生——计费按脱敏后文本估算，
     * 出网消息确定性脱敏）。</p>
     */
    public ReActAgent create(LoginUser user) {
        return create(user, null);
    }

    /**
     * 按显式登录用户+AGUI threadId 建 agent（createFromLogin 主入口）。
     * threadId 供附件工具按会话取附件（每 (user,thread) 各建 agent，
     * SessionKey 含 thread——同 thread 复用缓存 agent 时工具实例与 thread 绑定不变）。
     */
    public ReActAgent create(LoginUser user, String aguiThreadId) {
        // 批7 Q5/Q12-④: 优先级链第一段=用户会话选择(自选开关开时), 失效/关闸回退默认链
        DcsAiConfig cfg = modelFactory.resolveConfigWithUserChoice(user.getTenantId(), aguiThreadId);
        // 批7: 回写「本次建 agent 的选择基准」(picked 原值含回退), resolver 据此判失效
        org.dromara.assistant.core.AssistantModelChoiceHolder.markBuilt(aguiThreadId,
            org.dromara.assistant.core.AssistantModelChoiceHolder.get(aguiThreadId));
        // 业务批4 Q1-A：同刻回写白名单指纹基准（resolver 比对当前指纹判范围变化失效重建）
        // 业务批5 Q4-A：指纹扩「助手默认指向」段——管理员换默认后该租户缓存会话下请求即重建
        org.dromara.assistant.service.AssistantModelSettingService.ScopeAndDefault sd =
            modelSettingService.scopeAndDefault(user.getTenantId());
        org.dromara.assistant.core.AssistantModelChoiceHolder.markScopeBuilt(aguiThreadId,
            org.dromara.assistant.core.AssistantModelChoiceHolder.defaultAwareFingerprint(
                sd.scopeIds(), sd.defaultConfigId(), sd.usableSignature()));
        // 业务批3-④: 联网开关同款回写——工具集随开关增删, resolver 对比判缓存失效
        // N3 三审回修：租户从登录快照显式带下（异步 supplier 线程无 Sa-Token 上下文）
        boolean webSearchOn = org.dromara.assistant.core.AssistantWebSearchHolder.isOn(aguiThreadId);
        org.dromara.assistant.core.AssistantWebSearchHolder.markBuilt(aguiThreadId, webSearchOn);
        // 二站④ AU-53：租户开长期记忆才装配记忆链路（注入装饰器+save_memory 工具+§13 提示词）
        // 三审回修（dspro P2-6）：租户配置一次读全（此前 memoryOn/toolGate/compaction 各查一次，
        // 三次快照有半新半旧窗口）——toolGate 在下方注册面消费，compaction 在压缩中间件消费
        org.dromara.assistant.service.AssistantConfigService.EffectiveConfig tenantCfg =
            assistantConfigService.effective(user.getTenantId());
        // G1 遗留#3：租户装配面指纹（toolGate/memory/compaction）入链——改工具开关后
        // 该租户全部缓存会话下一请求即失效重建，不再吃 60 分钟会话超时
        org.dromara.assistant.core.AssistantModelChoiceHolder.markConfigBuilt(aguiThreadId,
            org.dromara.assistant.core.AssistantModelChoiceHolder.assemblyFingerprint(tenantCfg,
                AssistantCompactionPolicy.deriveTriggerTokens(cfg.getContextWindow(), cfg.getMaxTokens()),
                webSearchOn && org.dromara.assistant.tools.WebSearchTools
                    .hasUsableTenantKey(configService, user.getTenantId())));
        boolean memoryOn = "1".equals(tenantCfg.memoryEnabled());
        // AU-03(Q3=C)→批2 双层档位: 最内层套思考档装饰器——按会话读 thinkTier 经能力目录
        // 翻译原生参数(TIERS→reasoning_effort / SWITCH→thinking.type)；装配期解析能力一次注入。
        // 外两层不变（计费按出网文本估算,PII 先脱敏再计费）。
        // 二站④：记忆注入装饰器垫最内层（记忆先进上下文，再走思考档/PII/计费——
        // 计费按含记忆的真实出网文本估算，诚实口径）
        ToolIdentity identity = ToolIdentity.from(user);
        Model innermost = new ThinkingTierModelDecorator(modelFactory.buildModel(cfg, aguiThreadId), aguiThreadId,
            capabilityService.matchCapability(user.getTenantId(), cfg.getProvider(), cfg.getModelName()));
        // 五需求批 R1：技能当轮注入装饰器——用户选中技能的那几轮把正文以 system 消息追加
        // （每次模型调用现读 holder，不进会话记忆，摘掉即无）；链位在思考档外/记忆内
        innermost = new SkillInjectModelDecorator(innermost, aguiThreadId, identity.getRoleLevel());
        // Q8（2026-10-15 拍板A）：当前日期锚点——每轮尾部追加「今天是 …」（模型此前
        // 没有「今天」，日期类问题靠时间戳倒猜）。装饰器一律尾部追加且外层先追加，
        // 装配 Skill→Date→Memory 后实际消息序 [原文…, 记忆段, 日期段, 技能段]（选中
        // 技能的轮次技能段才是末位；三席审查探针实测）。尾部=动态区，不碰缓存前缀（Q4 红线）。
        innermost = new DateInjectModelDecorator(innermost);
        if (memoryOn) {
            innermost = new MemoryInjectModelDecorator(innermost, memoryService,
                user.getTenantId(), user.getUserId());
        }
        Model model = new BillingModelDecorator(
            new org.dromara.assistant.security.AssistantPiiModelDecorator(
                innermost, piiFilter, user.getTenantId()),
            cfg, user.getUserId(), aiBillingService, tenantWalletService,
            modelPriceService, costLedger, aguiThreadId)
            // 五需求批 R5：注入积分服务——订阅租户对话走积分池（周池+5h 窗+加油包），
            // 未订阅零行为变化。六审勘误（longcat P2）：侧车（标题/追问）同样走本装饰器
            // （装饰器包住整个 model，侧车调用与正文共用一条计费链），旧注释"侧车不注入"
            // 与实际不符——侧车小额消耗同样走积分，与钱包口径行为一致
            .creditService(creditService)
            // AI 缺口批 Q8：主对话链接钥匙池上报（失败冷却/成功清冷却；侧车不接零影响）
            .keyPool(providerKeyPool);
        Toolkit toolkit = new Toolkit();
        // S-3 单点化：工具注册+权限规则同循环生成（ToolRegistry 唯一登记处，
        // 替代原 toolkit.registerTool 散调 + buildPermissionContext 硬编码名数组）
        PermissionContextState.Builder pb = PermissionContextState.builder();
        Set<String> registered = ToolRegistry.registerAll(toolkit, identity,
            new ToolRegistry.ToolDeps(
                configService, piiFilter, attachmentService,
                planSignal, memoryService, memoryOn, aguiThreadId, user.getTenantId(),
                todoSignal, conversationService, webSearchQuota, quotaService), pb,
            // 批1 Q1=B：租户级工具开关矩阵（tool_gate JSON）随会话生效——同租户同闸，
            // 管理员改完下一轮建会话即生效（注册面即时消失）
            tenantCfg.toolGate());
        // 批2 SPI 化: BizToolProvider 通道(内置优先, 撞名让位)——注册与权限规则同循环;
        // 批3 起业务工具唯一来路（微核心 DcsToolProvider 在此挂载）
        registered.addAll(providerToolRegistry.registerProviders(toolkit, identity, aguiThreadId, pb));
        // 批2 Q2=A：MCP 外部工具试点挂载（平台级启用行；单服务失败=审计+warn 跳过，绝不连坐会话）
        // G4-#4：租户工具闸随行传入（mcp:<server> 键，off/admin 生效于挂载面）+ 用户角色档
        registered.addAll(mcpPilotService.attachEnabled(toolkit, user.getTenantId(), user.getUserId(),
            aguiThreadId, pb, tenantCfg.toolGate(), identity.getRoleLevel()));
        ReActAgent agent = ReActAgent.builder()
            .name(AGENT_ID)
            // 批F2：速查卡按注册面裁剪——无权工具名留在提示词里，模型照词幻觉调用也会
            // 弹死卡（agentscope 对未注册工具默认 ASK，E2E 实证）；registered=本角色真注册集
            // Q5（2026-10-15 拍板A）：催办纪律段同机制——注册面含催办工具才装配。批3 微核心化
            // 后平台工具无催办类，运行口径恒不装配（展示口径 sysPrompt(memoryOn) 仍全量）；
            // 业务侧催办工具若回归，走 SPI 注册名判断恢复装配
            .sysPrompt(PromptAssets.filterDiscipline(
                PromptAssets.filterCheatsheet(
                    PromptAssets.sysPrompt(memoryOn, false),
                    registered),
                registered) + SkillAssets.catalog(identity.getRoleLevel()))
            .model(model)
            .stateStore(stateStore)
            .toolkit(toolkit)
            .permissionContext(pb.build())
            // 稳流① Q1（2026-10-20 拍板 A①）：流层真事件心跳——20s 一帧 dcs-heartbeat
            // CUSTOM 帧（合法静默期也出网：联网搜索/慢首 token/自动压缩摘要期），喂前端
            // 600s 看门狗+刷新 Recorder lastEventAt（工具/压缩段豁免的事件层实现）。
            // order=0 链位最外层，包住压缩中间件的 onReasoning
            .middleware(new DcsHeartbeatMiddleware())
            // 批A-1（2026-10-22 拍板 Q1/Q5）：自动压缩通道——触发线按当前模型窗口派生
            // （窗口 − 输出预留 2.1 万 − 缓冲 1.3 万），不再读租户固定阈值；尾窗按轮保留
            // 最后 1 轮原文。配置双显式（trigger+keep），不依赖模型自报窗口（P14 Step5
            // 实证 deepseek 族自报 0）。
            // 批A-2：摘要调用走 CompactionSummaryModel 包装（输出封顶 2 万、工具恒禁）
            .middleware(new DcsCompactionMiddleware(workspaceManager,
                new CompactionSummaryModel(model),
                AssistantCompactionAssets.buildConfig(
                    AssistantCompactionPolicy.deriveTriggerTokens(cfg.getContextWindow(), cfg.getMaxTokens())),
                conversationService))
            // 业务批7 Q16-①：恢复态 pending（无结果的 tool_use）官方恢复分支——合成工具
            // 失败结果后会话继续；配合 AssistantStaleAskingRecovery 启动翻转 asking→allowed，
            // 根治「重启后旧确认挂起毒化 thread」（ASKING 分支在恢复开关前硬抛英文错）
            .enablePendingToolRecovery(true)
            .build();
        log.debug("[dcs-assistant] 为租户 {} 用户 {} 创建会话 agent（含 {} 个工具, 长期记忆={})",
            user.getTenantId(), user.getUserId(), registered.size(), memoryOn ? "开" : "关");
        return agent;
    }

    /**
     * 供 registerFactory 的 Supplier 引用：取 resolver 身份桥里的登录用户建租户 agent。
     *
     * <p>supplier 在响应式线程池执行（无 Sa-Token 上下文，12机 冒烟实证），身份由
     * {@link AssistantIdentityHolder} 从同线程的 resolver 传递；取不到=401 fail-closed。</p>
     */
    public ReActAgent createFromLogin() {
        AssistantIdentityHolder.Entry entry = AssistantIdentityHolder.consumeEntry();
        if (entry.user() == null) {
            throw new ServiceException("登录状态无效，无法创建助手会话", 401);
        }
        try {
            return create(entry.user(), entry.aguiThreadId());
        } catch (RuntimeException e) {
            // Q4/ISSUE-007①（2026-10-14）：框架把建会话异常走 contractErrorEvents 直发 SSE、
            // 不经 enricher——resolver 已登记的 run 行（RUNNING）零事件，60s 收割器因
            // lastEventAt 未注册而看不见它，此前只能等 15min RetentionJob 兜底（假"生成中"
            // +额度被占 15 分钟）。这里在抛回框架前同步终局化：FAILED/'agent-build-failed'
            // +按回读实际状态退回合（防双退守卫与 finishRun 尾部同款）；真实失败原因由框架
            // 错误帧当场告知用户，日志侧留 e.getMessage()。中段流内错误仍走 RUN_ERROR 通道
            // （recorder 已正确处理，不经本刀）。
            log.warn("[AgentFactory] 建会话失败, 同步终局化 run={}: {}", entry.runId(), e.getMessage());
            runRecorder.finalizeBuildFailure(entry.runId(), "agent-build-failed");
            throw e;
        }
    }

    /**
     * 能力全景只读展示（批A Q4，2026-10-10）：管理页「系统提示词」折叠区数据源。
     * 与 create() 的拼接口径同源（memoryOn 时含 §14 记忆段）；
     * 批2 Q8 起分段视图走 {@link PromptAssets#segments(boolean)}（Panorama 端点）。
     */
    public static String sysPromptText(boolean memoryOn) {
        return PromptAssets.sysPrompt(memoryOn);
    }
}
