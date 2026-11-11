package org.dromara.assistant.core;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
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
    /** 动作账本(三批③ 2026-11-01: 写操作审计 hook 落行/收口的 service 依赖) */
    private final org.dromara.assistant.service.AssistantActionService actionService;

    private final org.dromara.assistant.service.McpPilotService mcpPilotService;
    /** 题2 案3（2026-10-31）：技能使用统计——取用一次记一行，管理页展示「用了几次/最近一次」 */
    private final org.dromara.assistant.service.AssistantSkillUsageService skillUsageService;
    /** 题12 案1：结构化反问桥（ask_user 挂起等用户点选） */
    private final org.dromara.assistant.core.AssistantAskUserBridge askUserBridge;
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
     * 自动化无人值守入口（2026-11-05 拍板 题2 甲）：同一套装配，但只保留只读工具——
     * 平台侧仅 view_skill / load_skill_through_path，业务侧只留 {@code BizToolSpec.readOnly=true}
     * 的查询工具；MCP 外部工具默认不挂（可能带写操作）。执行时也不会有确认卡可点，
     * 所以写类/交互类（ask_user / submit_plan / todo_write / save_memory）必须裁掉。
     *
     * <p>旧板题 3 乙（2026-11-06）：自动化可以显式勾选只读外部服务——默认空=一个不挂；
     * 名单里的服务由 McpPilotService 二次过滤（只读才挂），挂进来的工具并入只读白名单。</p>
     */
    public ReActAgent createReadOnly(LoginUser user, String aguiThreadId) {
        return create(user, aguiThreadId, true, null, null);
    }

    public ReActAgent createReadOnly(LoginUser user, String aguiThreadId, java.util.Set<String> mcpServers) {
        return create(user, aguiThreadId, true, mcpServers, null);
    }

    /**
     * 自动化专用：额外回传**实际挂上**的只读外部工具名（三审 P2，2026-11-07）——
     * 失败计数要按「真的挂上了没」判，而不是按用户勾选名单猜（勾了但服务下线/闸关＝零外部因素）。
     */
    public ReActAgent createReadOnly(LoginUser user, String aguiThreadId, java.util.Set<String> mcpServers,
                                     java.util.Set<String> mountedOut) {
        return create(user, aguiThreadId, true, mcpServers, mountedOut);
    }

    /**
     * 按显式登录用户+AGUI threadId 建 agent（createFromLogin 主入口）。
     * threadId 供附件工具按会话取附件（每 (user,thread) 各建 agent，
     * SessionKey 含 thread——同 thread 复用缓存 agent 时工具实例与 thread 绑定不变）。
     */
    public ReActAgent create(LoginUser user, String aguiThreadId) {
        return create(user, aguiThreadId, false, null, null);
    }

    private ReActAgent create(LoginUser user, String aguiThreadId, boolean readOnly,
                              java.util.Set<String> readOnlyMcpServers, java.util.Set<String> mountedOut) {
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
        // 题9 案1：自主权档位同款回写——权限规则建时固化，档位翻转由 resolver 判缓存失效
        org.dromara.assistant.core.AssistantAutonomyHolder.markBuilt(aguiThreadId,
            org.dromara.assistant.core.AssistantAutonomyHolder.level(aguiThreadId));
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
        innermost = new SkillInjectModelDecorator(innermost, aguiThreadId, identity.getRoleLevel(),
            tenantCfg.toolGate(), identity.getTenantId());
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
            .keyPool(providerKeyPool)
            // 2026-11-11 Q2-2 补丁：模型失败侧落问题行——框架对服务商故障不出 RUN_ERROR
            // （活体实证见 BillingModelDecorator#issueRecorder 注释），主链接上捕获链
            .issueRecorder(conversationService);
        Toolkit toolkit = new Toolkit();
        // S-3 单点化：工具注册+权限规则同循环生成（ToolRegistry 唯一登记处，
        // 替代原 toolkit.registerTool 散调 + buildPermissionContext 硬编码名数组）
        PermissionContextState.Builder pb = PermissionContextState.builder();
        // 题7 三席回修：驱逐落盘与 read_file 根都按「租户/用户/会话」分目录——
        // 工作区是进程级单例，不分目录=全租户共用一盘，任何会话拿到路径就能读别人的工具结果
        java.nio.file.Path threadWorkspace = threadWorkspace(user.getTenantId(), user.getUserId(), aguiThreadId);
        // 三批③（2026-11-01）：写操作审计——建时快照放行口径。autoAllow 谓词与下方
        // registerProviders 传同一条实例（=与实际装进引擎的权限规则一致）；hook 内含
        // AuditScope（writeTools/autonomyAllowed 两集合）与建时档位，release_mode 据此判定——
        // always 档经确认卡放行的工具不在快照集，必记 confirm 不误记 auto/once
        java.util.function.Predicate<String> autoAllow =
            name -> org.dromara.assistant.core.AssistantAutonomyHolder.autoAllows(aguiThreadId, name);
        ProviderToolRegistry.AuditScope auditScope =
            providerToolRegistry.auditScope(identity, aguiThreadId, autoAllow);
        AssistantActionLedgerHook actionLedgerHook = new AssistantActionLedgerHook(
            actionService, user.getTenantId(), user.getUserId(), aguiThreadId, auditScope);
        Set<String> registered = ToolRegistry.registerAll(toolkit, identity,
            new ToolRegistry.ToolDeps(
                configService, piiFilter, attachmentService,
                planSignal, memoryService, memoryOn, aguiThreadId, user.getTenantId(),
                todoSignal, conversationService, webSearchQuota, quotaService, tenantCfg.toolGate(),
                // 题7 案1：工作区根 = 本会话驱逐落盘目录（read_file 只在此根下回读）
                threadWorkspace,
                // 题12 案1：结构化反问桥（ask_user）
                askUserBridge,
                // 三批③：写操作审计挂钩（save_memory 平台放行也落账本）
                actionLedgerHook,
                // Q5 甲：自动化只读装配没有真人可答，ask_user 连 schema 都不注册
                !readOnly), pb,
            // 批1 Q1=B：租户级工具开关矩阵（tool_gate JSON）随会话生效——同租户同闸，
            // 管理员改完下一轮建会话即生效（注册面即时消失）
            tenantCfg.toolGate());
        // 批2 SPI 化: BizToolProvider 通道(内置优先, 撞名让位)——注册与权限规则同循环;
        // 批3 起业务工具唯一来路（微核心 DcsToolProvider 在此挂载）
        // 题9 案1：写类工具是否按用户档位直接放行（全自动/同类已确认）——运行前档位在 Holder 里
        // 三批③：同一条 autoAllow 谓词 + 审计 hook 传入（写类业务工具执行前后落账本）
        registered.addAll(providerToolRegistry.registerProviders(toolkit, identity, aguiThreadId, pb,
            autoAllow, actionLedgerHook));
        // 批2 Q2=A：MCP 外部工具试点挂载（平台级启用行；单服务失败=审计+warn 跳过，绝不连坐会话）
        // G4-#4：租户工具闸随行传入（mcp:<server> 键，off/admin 生效于挂载面）+ 用户角色档
        // 自动化只读模式：默认整段跳过；只有显式勾选的**只读**服务才挂（题3 乙三条硬规矩）
        java.util.Set<String> readOnlyMcpTools = java.util.Set.of();
        if (!readOnly) {
            registered.addAll(mcpPilotService.attachEnabled(toolkit, user.getTenantId(), user.getUserId(),
                aguiThreadId, pb, tenantCfg.toolGate(), identity.getRoleLevel()));
        } else if (readOnlyMcpServers != null && !readOnlyMcpServers.isEmpty()) {
            readOnlyMcpTools = mcpPilotService.attachEnabled(toolkit, user.getTenantId(), user.getUserId(),
                aguiThreadId, pb, tenantCfg.toolGate(), identity.getRoleLevel(), readOnlyMcpServers);
            registered.addAll(readOnlyMcpTools);
        }
        // 三审 P2：回传实际挂载结果（0 个工具＝这轮没有任何外部因素可归因）
        if (mountedOut != null) {
            mountedOut.addAll(readOnlyMcpTools);
        }
        if (readOnly) {
            // 只读白名单：技能正文（load_skill_through_path 由下方中间件注册）+ view_skill
            // + read_file（只在会话驱逐落盘目录内回读，纯读）+ 业务侧 readOnly 查询工具。
            // 其余（web_search/read_attachment/save_memory/submit_plan/ask_user/todo_write
            // 及各写类业务工具）一律摘除。
            // read_file 必须留：大结果被驱逐后模型要靠它回读，摘掉就只能交半截答案
            //（席1 P2-D5：结果驱逐中间件对只读模式同样生效）。
            java.util.Set<String> keep = new java.util.HashSet<>(
                java.util.Set.of("view_skill", "load_skill_through_path", "read_file"));
            keep.addAll(providerToolRegistry.readOnlyToolNames(identity));
            // 勾选的只读外部工具：挂载成功才在 keep 里（挂失败自然不在，不留空名）
            keep.addAll(readOnlyMcpTools);
            for (String name : new java.util.ArrayList<>(toolkit.getToolNames())) {
                if (!keep.contains(name)) {
                    toolkit.removeTool(name);
                }
            }
            // 速查卡按真注册面裁剪（同下方 sysPrompt 注释的纪律）：被摘掉的工具名不能留在提示词里
            registered.retainAll(keep);
        }
        // B 批装载替换（2026-10-27 题2=甲/题3=甲）：技能面迁官方 DynamicSkillMiddleware——
        // sysPrompt 不再拼自研目录（SkillAssets.catalog 摘除），每次调用由官方中间件按可见
        // 视图重组官方 <skills> 段；我们的子类 AssistantSkillGateMiddleware 管「谁能用哪个技能」
        // （minRole + 工具开关矩阵 skill:<name> 行，语义与工具行同构）。矩阵改动走装配面指纹
        // 失效缓存会话→下一请求重建即生效（题4 甲：新对话不可见，进行中对话不踢）。
        AssistantSkillGateMiddleware skillGate =
            new AssistantSkillGateMiddleware(toolkit, identity.getRoleLevel(), tenantCfg.toolGate(),
                identity.getTenantId());
        // 官方 load_skill_through_path 工具随中间件注册；权限补 ALLOW 规则（只读取正文，
        // 与 view_skill 同口径，不赌引擎 passthrough 默认）
        pb.addAllowRule("load_skill_through_path",
            new PermissionRule("load_skill_through_path", "",
                PermissionBehavior.ALLOW, "dcs-assistant"));
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
                registered))
            .model(model)
            .stateStore(stateStore)
            .toolkit(toolkit)
            .permissionContext(pb.build())
            // 官方预留通道：dynamicSkillsEnabled(false)=由我们子类接管技能动态段
            // （builder.skillRepository 自动装的是基类，读不到矩阵）
            .dynamicSkillsEnabled(false)
            .middleware(new AssistantPlanGateMiddleware(planSignal,
                name -> {
                    io.agentscope.core.tool.AgentTool t = toolkit.getTool(name);
                    return t != null && t.isReadOnly();
                }, aguiThreadId))
            .middleware(skillGate)
            // 题2 案3（2026-10-31）：技能取用统计——官方 load_skill_through_path 与遗留
            // view_skill 成功返回才 +1；租户显式传入（运行时线程无 Sa-Token）。
            // 收口回修：手动胶囊已由 resolver 按请求记过，同会话同技能这里去重
            .middleware(new AssistantSkillUsageMiddleware(skillUsageService, user.getTenantId(), user.getUserId(),
                identity.getRoleLevel(), tenantCfg.toolGate(), aguiThreadId))
            // 稳流① Q1（2026-10-20 拍板 A①）：流层真事件心跳——20s 一帧 dcs-heartbeat
            // CUSTOM 帧（合法静默期也出网：联网搜索/慢首 token/自动压缩摘要期），喂前端
            // 600s 看门狗+刷新 Recorder lastEventAt（工具/压缩段豁免的事件层实现）。
            // order=0 链位最外层，包住压缩中间件的 onReasoning
            .middleware(new DcsHeartbeatMiddleware())
            // 题7 案1（2026-10-31）：超大工具结果驱逐——单条结果超阈值（默认 8 万字符）时落盘
            // 工作区 large_tool_results/<agent>/，上下文只留头 2000 字预览 + 路径，
            // 模型追问细节用 read_file 分页回读（ToolRegistry 同名工具）。
            // 排在压缩中间件之前：先把单条超大结果卸掉，压缩只处理消息条数问题。
            // 题①（2026-10-31 拍板 B）：阈值=sys_config 两级读取（本租户→默认租户→80000），
            // 刻意不进装配指纹——缓存 agent 不重建，只影响新建会话。
            .middleware(new io.agentscope.harness.agent.middleware.ToolResultEvictionMiddleware(
                new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(threadWorkspace),
                io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig.builder()
                    .maxResultChars(AssistantEvictionThreshold.read(configService, user.getTenantId()))
                    .build()))
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
        // 符合性审查修复（dspro P1，2026-10-31）：框架 loadOrCreateAgentStateForSlot 对已有会话
        // 直接复用持久化 state——builder 上的新 permissionContext 进不了旧 state，旧会话档位
        // 翻转后规则仍是旧的（auto→always 降级方向 fail-open）。官方口子 replacePermissionContext：
        // 把建侧新规则装进该会话持久化 state 并刷新引擎缓存。只在「权限面变化触发的重建」
        // （resolver 置标记：档位翻转/批准代次/租户装配指纹）时做——普通模型切换重建不动旧
        // state，避免抹掉框架运行期可能记下的放行决定。
        if (aguiThreadId != null && !aguiThreadId.isBlank()
            && org.dromara.assistant.core.AssistantAutonomyHolder.consumeRuleInstallPending(aguiThreadId)) {
            try {
                agent.replacePermissionContext("t:" + user.getTenantId() + ":" + user.getUserId(),
                    aguiThreadId, pb.build());
                log.info("[AgentRebuild] 权限面变化, 新规则已装回旧会话 state thread={}", aguiThreadId);
            } catch (Exception e) {
                // 装载失败不阻断会话（本实例引擎缓存已带新规则，本次运行仍按新规则），
                // 但持久化可能停留旧规则——warn 留痕，重启后如再翻转会重新装
                log.warn("[AgentRebuild] 权限规则装回旧会话 state 失败 thread={}：{}", aguiThreadId, e.getMessage());
            }
        }
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
     * 本会话的工作区子目录（题7 三席回修）：{@code s/<租户>/<用户>/<会话>}。
     * 驱逐落盘与 read_file 共用这一个根，跨租户/跨会话互不可见；目录不存在则建（失败只降级
     * 到原共享根之外建不了——照旧返回路径，读工具会按「文件不存在」人话兜底，不炸会话）。
     */
    private java.nio.file.Path threadWorkspace(String tenantId, Long userId, String threadId) {
        java.nio.file.Path base = workspaceManager.getWorkspace();
        String scope = "s/" + safeSegment(tenantId) + "/" + (userId == null ? "0" : userId)
            + "/" + safeSegment(threadId);
        java.nio.file.Path dir = base.resolve(scope).normalize();
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (Exception e) {
            log.debug("[AgentFactory] 工作区子目录创建失败（读工具会按不存在兜底）: {}", e.getMessage());
        }
        return dir;
    }

    /** 路径段净化：只留字母数字与 _-，其余换 _；空段给 anon，最长 80 字符 */
    private static String safeSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "anon";
        }
        String s = raw.replaceAll("[^A-Za-z0-9_-]", "_");
        return s.length() > 80 ? s.substring(0, 80) : s;
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
