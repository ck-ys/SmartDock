package org.dromara.assistant.billing;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.DcsAiModelPrice;
import com.smartdock.service.IDcsAiCreditService;
import com.smartdock.service.IDcsAiModelPriceService;
import com.smartdock.service.support.AiBillingService;
import com.smartdock.service.support.AiTokenUsageSnapshot;
import com.smartdock.service.support.TenantWalletService;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 助手会话计费装饰器(批D D1-①): 包装 AgentScope Model, 每次调用记 usage + 钱包扣费。
 *
 * <p>设计要点:</p>
 * <ul>
 *   <li><b>不缓冲流</b>: doOnNext 只聚合 usage 与回显字符数, 响应 token 逐个透传;
 *       doFinally 同步落账(计费三写事务化, 见 AiBillingService#recordTransactional)。</li>
 *   <li><b>服务端序号</b>: 本装饰器实例(=一个会话 agent)AtomicLong 自增, 幂等键
 *       "ai-assistant:{tenant}:{configId}:{seq}"。六审勘误(dspro P1-3): 键含 instanceId
 *       ⇒ 实际防的是「同一订阅内重复触发 doFinally」; 跨订阅/agent 重建会生成新键,
 *       重放将双记——known limitation, 勿再把本注释当跨订阅幂等保障读。</li>
 *   <li><b>前置余额闸</b>: 每次调用前查可用余额, 低于单回合门槛直接 Flux.error 友好话术,
 *       不产生任何模型费用; 事后余额不足由 recordTransactional 留痕免扣。</li>
 *   <li><b>usage 缺失三件套</b>(D1-⑤): 模型未回传 usage 时——断言(检测到缺失)→ 告警(WARN 日志)
 *       → 兜底按字符数估算 token(估算偏保守多计, 防免费用), scene 打 "est" 标记可筛查。</li>
 * </ul>
 *
 * <p>计费失败不吞会话: recordTransactional 抛出的非预期异常在 doFinally 捕获只记 ERROR,
 * 本次回复已发完不受影响; 前置闸才是拦截点。</p>
 */
@Slf4j
public class BillingModelDecorator implements Model {

    /** 计费调用方标识(usage.caller, 对应钱包 service_code=AI_ASSISTANT_CHAT) */
    public static final String CALLER = "ASSISTANT_CHAT";

    /** 单回合最低可用余额(元): 低于即前置拦截。v4-flash 价位下一回合成本约 0.001~0.01 元 */
    private static final BigDecimal MIN_TURN_BALANCE = new BigDecimal("0.01");

    /** usage 缺失时的字符→token 估算系数(dsflash 复核: 中文≈1 token/字, 取 1.0 防少计; 英文多计=防免费用方向可接受) */
    private static final double CHARS_TO_TOKENS = 1.0;

    /** usage 缺失时输出 token 下限(模型隐性开销: 提示模板/推理前缀, 兜底多计) */
    private static final int EST_OUTPUT_FLOOR = 256;

    /** 欠费地板价 Redis 键前缀(glm53 P2-2: 事后不足免扣的租户, 下回合门槛抬到欠额×10, 余额充够自动放行) */
    private static final String FLOOR_KEY_PREFIX = "dcs:assistant:billing:floor:";

    /** 地板价上限(元): 欠费抬门槛的封顶, 防异常大单把租户永久锁死 */
    private static final BigDecimal FLOOR_CAP = new BigDecimal("1.00");

    /** 地板价 TTL: 兜底自动过期, 充值路径之外的保险丝 */
    private static final java.time.Duration FLOOR_TTL = java.time.Duration.ofDays(7);

    private final Model delegate;
    private final DcsAiConfig config;
    private final Long userId;
    private final AiBillingService billingService;
    private final TenantWalletService walletService;
    /** 模型价格服务(前置闸动态门槛: 按当前价估算本回合成本) */
    private final IDcsAiModelPriceService priceService;
    /** 装饰器实例雪花号(每会话 agent 一个): 幂等键的实例段——seq 只在本实例内递增,
     * 没有实例段时新会话首笔(seq=1)会撞历史会话同键被重放短路=免费(坑73, E2E 实证) */
    private final long instanceId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
    private final AtomicLong serverSeq = new AtomicLong();
    /** 回合成本累加器(批E E4): 逐笔累计, enricher 在 RUN_FINISHED 前取走发 cost_meta 帧 */
    private final AssistantCostLedger costLedger;

    /** 批6-#2：在飞收割判别键（会话 threadId；null=不参与判别——侧车） */
    private final String threadKey;
    /** Q8 钥匙池上报（可空：侧车/单测不注入） */
    private com.smartdock.service.support.ProviderKeyPool keyPool;
    /** goal 加固 H5：scene 场景后缀（空=主链零变化；侧车传 "|sidecar:followup"/"|sidecar:title"，
     *  对账④的「助手计费不平」检查与成本报表据此拆分主链/侧车） */
    private String sceneTag = "";

    /** R5 积分模式（2026-10-13）：null=钱包计费（原路径零改动）；主链注入后订阅租户走积分前置闸+积分落账 */
    private IDcsAiCreditService creditService;

    /** goal 加固 H5：链式设置场景后缀（构造器保持七参不动，主链调用零改动） */
    /**
     * AI 缺口批 Q8（2026-10-14）：注入厂商钥匙池——流失败按错误分类冷却「本回合用的那把
     * 钥匙」、成功清冷却（归因口径见 ProviderKeyPool 类头：内存记最后 pick，低 QPS 并发
     * 窗口极小+自愈）。null=不上报（侧车/单测路径零行为变化）。
     */
    public BillingModelDecorator keyPool(com.smartdock.service.support.ProviderKeyPool pool) {
        this.keyPool = pool;
        return this;
    }

    public BillingModelDecorator sceneTag(String tag) {
        this.sceneTag = tag == null ? "" : tag;
        return this;
    }

    /** R5：链式注入积分服务（AssistantAgentFactory 主链；侧车/单测不传=钱包计费） */
    public BillingModelDecorator creditService(IDcsAiCreditService svc) {
        this.creditService = svc;
        return this;
    }

    public BillingModelDecorator(Model delegate, DcsAiConfig config, Long userId,
                                 AiBillingService billingService, TenantWalletService walletService,
                                 IDcsAiModelPriceService priceService, AssistantCostLedger costLedger) {
        this(delegate, config, userId, billingService, walletService, priceService, costLedger, null);
    }

    /** 批6-#2 扩参：threadKey=会话 threadId（在飞收割判别键，主链注入；侧车/单测走七参
     *  重载传 null=跳过标记——侧车不登记 run，不在收割判别域内） */
    public BillingModelDecorator(Model delegate, DcsAiConfig config, Long userId,
                                 AiBillingService billingService, TenantWalletService walletService,
                                 IDcsAiModelPriceService priceService, AssistantCostLedger costLedger,
                                 String threadKey) {
        this.delegate = delegate;
        this.config = config;
        this.userId = userId;
        this.billingService = billingService;
        this.walletService = walletService;
        this.priceService = priceService;
        this.costLedger = costLedger;
        this.threadKey = threadKey;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> toolSchemas, GenerateOptions options) {
        // 状态随订阅隔离(dspro P2-5): 聚合器/序号/前置闸全部下沉 defer——未来框架若冷流多订阅,
        // 各订阅独立聚合独立落账, 不再共享 lastUsage/replyChars 互相覆盖
        return Flux.defer(() -> doStream(messages, toolSchemas, options));
    }

    private Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> toolSchemas, GenerateOptions options) {
        int promptChars = countPromptChars(messages);
        // 三元门槛: 静态最低 0.01 + 欠费地板价 + 本回合估算成本(glm53 P2-2: 低余额免费敞口闭环)
        BigDecimal turnFloor = currentFloor();
        BigDecimal estimate = estimateTurnCost(promptChars);
        // 免费档(dspro 三审 P2): 无价行且按次单价 0(如 OPENCODE free 模型)——实扣恒 0 元,
        // 0.01 静态门槛会把零余额租户挡在免费模型外且话术误导「额度不足」; 欠费地板价同理不惩罚
        boolean zeroCostTier = estimate.signum() == 0;
        // R5 积分模式：订阅租户前置闸换积分判定（周池+5h 窗+tank），钱包闸整段跳过；
        // 免费档照旧直通（积分只在有价模型上消耗）。
        // 业务批8 Q7 丁（2026-10-14）：判定锚点从"有无生效订阅"改为租户显式消费方式开关
        // consumeMode——返回 CREDIT 即保证有生效订阅（无订阅的 CREDIT 行会被派发口径
        // 自动回写 WALLET），钱包/积分双轨由租户自己掌握，订阅成功自动切积分、到期自动切回
        boolean creditsMode = !zeroCostTier && creditService != null
            && "CREDIT".equals(creditService.consumeMode(config.getTenantId()));
        if (!zeroCostTier) {
            if (creditsMode) {
                // 1 积分=1 分钱：回合门槛=估算元值×100 向上取整，只判门槛不预留；
                // 业务批8 Q2：乘模型积分倍率（flash×2/旗舰×5 等，NULL=1）——重模型每回合吃更多积分
                long estCredits = Math.max(1, estimate.movePointRight(2)
                    .multiply(creditMultiplier(config)).setScale(0, RoundingMode.CEILING).longValue());
                IDcsAiCreditService.GateResult gate = creditService.preGate(config.getTenantId(), estCredits);
                if (gate.blocked()) {
                    log.warn("[AssistantBilling] 积分前置拦截: 租户 {} 估算 {} 积分",
                        config.getTenantId(), estCredits);
                    return Flux.error(new ServiceException(gate.message()));
                }
            } else {
                BigDecimal threshold = MIN_TURN_BALANCE.max(turnFloor).max(estimate);
                BigDecimal available = walletService.availableBalanceOf(config.getTenantId());
                if (available.compareTo(threshold) < 0) {
                    log.warn("[AssistantBilling] 前置拦截: 租户 {} 可用余额 {} 低于回合门槛 {}(floor={} est={})",
                        config.getTenantId(), available.toPlainString(), threshold.toPlainString(),
                        turnFloor.toPlainString(), estimate.toPlainString());
                    return Flux.error(new ServiceException("AI 助手额度不足（当前可用 " + available.toPlainString()
                        + " 元），请联系管理员充值后再试"));
                }
            }
        }
        long seq = serverSeq.incrementAndGet();
        long startAt = System.currentTimeMillis();
        AtomicReference<ChatUsage> lastUsage = new AtomicReference<>();
        AtomicLong replyChars = new AtomicLong();
        // 同一流多次订阅也只按一次调用落账(同订阅内; 跨订阅=新键新记, 见类头六审勘误)
        AtomicBoolean recorded = new AtomicBoolean(false);
        // 批6-#2：模型调用在飞标记（键=构造注入的 threadId——RunContext ThreadLocal 在
        // 首调订阅时刻尚未刷新（首批事件先于订阅是时序巧合不可依赖），静态键零时序依赖。
        // 侧车（标题/追问）传 null 跳过——它们不登记 run，不在收割判别域内）。
        // doFinally 里清——包括断连拆链的 CANCEL：清零后收割器才会放行
        final String inflightKey = threadKey;
        if (inflightKey != null && !inflightKey.isBlank()) {
            costLedger.markInFlight(inflightKey);
        }
        // R2-F1/ISSUE-001 修（2026-10-14）：delegate.stream(...) 若同步抛出（PII/思考档/
        // 记忆等装饰器装配期异常），doFinally 不建链——在飞计数永久留 1，该 thread 的
        // 60s 收割永久失能（退化为 15min 兜底）+ inFlights 队首钉死。装配期同步抛出在
        // 此显式清零后再原样上抛（Flux.error 走不到订阅者订阅前的 doFinally，这是
        // Reactor 语义缺口，只能手工补偿）。
        reactor.core.publisher.Flux<ChatResponse> stream;
        try {
            stream = delegate.stream(messages, toolSchemas, options);
        } catch (RuntimeException e) {
            if (inflightKey != null && !inflightKey.isBlank()) {
                costLedger.clearInFlight(inflightKey);
            }
            throw e;
        }
        return stream
            // Q8 钥匙池上报：失败按错误分类冷却（429=10min / 401·402·403=30min）、
            // 成功清冷却。必须挂在 onErrorMap 之前——映射后的 ServiceException 人话
            // 文案不含状态码，classify 就抓不到了
            .doOnError(err -> {
                if (keyPool != null) {
                    keyPool.reportFailure(config.getTenantId(), config.getProvider(), err);
                }
            })
            .doOnComplete(() -> {
                if (keyPool != null) {
                    keyPool.reportSuccess(config.getTenantId(), config.getProvider());
                }
            })
            // 业务批7 Q13：agentscope 框架模型重试耗尽抛 RetryExhaustedException（英文
            // "Retries exhausted: 2/2" 原样直出界面）——此处统一映射人话；侧车链自身
            // 吞异常不受影响，正常失败原因（非重试耗尽）原样透传不遮蔽。
            // 类位实证（jar -tf reactor-core-3.7.18）：该异常是 reactor.core.Exceptions 的
            // 包级私有内部类，外部无法引用类字面量——改谓词式匹配（类名精确+消息前缀兜底）
            .onErrorMap(
                // dspro P2-5：Exceptions.unwrap 先剥 reactor 包装（CompletionException 等），
                // 否则被包一层的重试耗尽英文原文仍会漏到界面
                ex -> {
                    Throwable t = reactor.core.Exceptions.unwrap(ex);
                    return "reactor.core.Exceptions$RetryExhaustedException".equals(t.getClass().getName())
                        || (t.getMessage() != null && t.getMessage().startsWith("Retries exhausted"));
                },
                ex -> new ServiceException("模型连续重试后仍没有应答（可能正忙或网络不稳），请稍后再试，或先换个模型"))
            .doOnNext(response -> {
                if (response.getUsage() != null) {
                    lastUsage.set(response.getUsage());
                }
                if (response.getContent() != null) {
                    response.getContent().forEach(block -> {
                        if (block instanceof TextBlock text) {
                            replyChars.addAndGet(text.getText() == null ? 0 : text.getText().length());
                        }
                    });
                }
            })
            .doFinally(signal -> {
                // 批6-#2：在飞清零必须先于 recorded 早退——否则断连 CANCEL 路径（recorded
                // 已置位前的重入保护）会漏清，活 run 被永久豁免收割
                if (inflightKey != null && !inflightKey.isBlank()) {
                    costLedger.clearInFlight(inflightKey);
                }
                if (!recorded.compareAndSet(false, true)) {
                    return;
                }
                record(seq, promptChars, replyChars.get(), lastUsage.get(),
                    System.currentTimeMillis() - startAt, signal == reactor.core.publisher.SignalType.ON_ERROR, creditsMode);
            });
    }

    /**
     * 落账一次调用: 有 usage 用真实值; 缺失走估算兜底三件套(断言+告警+估算标记)。
     */
    private void record(long seq, int promptChars, long replyChars, ChatUsage usage,
                        long latencyMs, boolean failed, boolean creditsMode) {
        boolean estimated = usage == null;
        AiTokenUsageSnapshot snapshot;
        if (usage == null) {
            int promptTokens = Math.max(1, ceil(promptChars * CHARS_TO_TOKENS));
            int completionTokens = Math.max(EST_OUTPUT_FLOOR, ceil(replyChars * CHARS_TO_TOKENS));
            snapshot = new AiTokenUsageSnapshot(promptTokens, 0, completionTokens,
                promptTokens + completionTokens);
            // D1-⑤ 告警面: 未回传 usage 的模型调用(流被取消/无 include_usage)按估算计费
            log.warn("[AssistantBilling] 模型未回传 usage, 按字符估算计费: tenant={} seq={} promptChars={} replyChars={}",
                config.getTenantId(), seq, promptChars, replyChars);
        } else {
            snapshot = new AiTokenUsageSnapshot(usage.getInputTokens(), usage.getCachedTokens(),
                usage.getOutputTokens(), usage.getTotalTokens());
        }
        String scene = "assist:chat:seq" + seq + sceneTag
            // Q9（2026-10-15 拍板A）：scene 尾部带 8 位会话标识——纯留痕（不加列不聚合），
            // 将来按会话排错可查库；侧车（threadKey=null）不带
            + (threadKey != null && threadKey.length() >= 8
                ? ":th" + threadKey.substring(threadKey.length() - 8) : "")
            + (estimated ? "|est" : "");
        String idempotencyKey = "ai-assistant:" + config.getTenantId() + ":" + config.getConfigId()
            + ":" + instanceId + ":" + seq;
        // 六审回修（dspro P1-1/k3 P1-1 续）：取整口径与闸统一——付费调用最低 1 积分无条件落账
        // （原 rawCredits>0 才扣，真实成本 <0.005 元被 HALF_UP 抹 0 = 闸按 1 积分拦、落账 0 积分免单）；
        // 业务批8 Q2：乘模型积分倍率（与前置闸同源 creditMultiplier，两处口径必须一致）
        final Long creditsToCharge = creditsMode && !failed
            ? Math.max(1, priceOf(snapshot).movePointRight(2)
                .multiply(creditMultiplier(config))
                .setScale(0, RoundingMode.HALF_UP).longValue())
            : null;
        // Q9（2026-10-15 拍板A，ZCode 同构）：主链真实回传才进会话级命中聚合——估算/侧车/
        // 失败行不计（与状态卡口径一致）；口径=缓存读÷总输入（input 已含 cached，不重复加）
        if (!estimated && !failed && threadKey != null && !threadKey.isBlank()) {
            org.dromara.assistant.core.AssistantCacheHitHolder.record(threadKey,
                snapshot.promptTokens(), snapshot.cachedPromptTokens());
        }
        try {
            // 租户上下文钉死(dspro P2-6): 落账跑在 boundedElastic 无请求上下文,
            // TenantLineHandler 在租户为空时整表放行——显式 dynamic 防未来新增查询静默跨租户
            org.dromara.common.tenant.helper.TenantHelper.dynamic(config.getTenantId(), () -> {
                if (creditsMode) {
                    // R5 积分模式（六审回修 dspro P0-1 方案A）：usage 单据与积分扣减在同一事务
                    // 内完成（creditsToCharge 由上面按 priceOf 镜像算好传入），不再事务外另扣——
                    // 消除"单据在流水无"的静默免单窗口
                    billingService.recordTransactionalCredits(config, CALLER, scene, snapshot,
                        latencyMs, !failed, failed ? "model stream error" : null, userId, idempotencyKey,
                        creditsToCharge);
                } else {
                    billingService.recordTransactional(config, CALLER, scene, snapshot,
                        latencyMs, !failed, failed ? "model stream error" : null, userId, idempotencyKey);
                }
                return null;
            });
            if (!creditsMode && !failed && estimateTurnCost(promptChars).signum() != 0) {
                // AU-04: 地板价只针对「正常完成但没扣到钱」(真欠费/非本币)——上游故障
                // (timeout/5xx/流中断, failed=true)同样无 ledger, 但不是租户欠钱,
                // 抬门槛=一次模型商故障惩罚全租户 7 天(话术还会误导「额度不足」);
                // 免费档(实扣恒 0, 如 OPENCODE free 模型)无 ledger 是常态而非欠费, 同样不写(dspro 三审 P2)
                raiseFloorIfUnpaid(idempotencyKey, promptChars);
            }
            if (log.isDebugEnabled()) {
                log.debug("[AssistantBilling] 落账 tenant={} seq={} est={} latencyMs={}",
                    config.getTenantId(), seq, estimated, latencyMs);
            }
        } catch (Exception e) {
            // 计费异常不吞会话(回复已完成); 钱包侧差异由对账 job(检查④)兜住——积分侧
            // 六审回修后 usage 与流水同事务, 不再产生单边提交(同事务整体回滚=两侧一致地都无)
            log.error("[AssistantBilling] 落账失败 tenant={} seq={} idem={}",
                config.getTenantId(), seq, idempotencyKey, e);
        }
        // 批E E4: 本笔折算金额累计进回合账(enricher RUN_FINISHED 前取走发 cost_meta)。
        // runId 来自流线程 ThreadLocal——record 落在非流线程时为 null, 角标缺省无害降级
        try {
            String runId = org.dromara.assistant.core.AssistantRunContext.currentRunId();
            if (runId != null && !runId.isBlank()) {
                costLedger.accumulate(runId, snapshot.totalTokens(), priceOf(snapshot));
                // 批B B1（glm53 P1-1 回修）：末次调用快照（覆写 last-wins）供消息表
                // total_tokens——上下文占用=最后子调用 usage.totalTokens()=input+output。
                // ⚠️ input(OpenAI prompt_tokens)已含 cached，再叠加会虚高近 2 倍；
                // 禁累计求和（多子调用各重发全量历史，cline 公式）。
                // 仅真实 usage 记（估算值不算上下文真值，宁缺毋假）
                if (!estimated) {
                    costLedger.recordLastUsage(runId, snapshot.totalTokens() == null ? 0L : snapshot.totalTokens());
                }
            }
        } catch (Exception e) {
            log.debug("[AssistantBilling] 成本累计跳过: {}", e.getMessage());
        }
    }

    /**
     * snapshot 按当前生效价折算元值(E4 显示口径): input×入价 + output×出价,
     * cached 折扣忽略(显示值保守偏高, 账本流水仍是唯一真源)。
     * AU-43(1008)：无价格行/按次计费时走 fixedUnitPrice——与实扣(AiBillingCalculator#fixed)
     * 同口径, 消灭「预估 ¥0.0000 → 实扣 ¥0.0100」跳变观感。
     */
    private BigDecimal priceOf(AiTokenUsageSnapshot snapshot) {
        try {
            DcsAiModelPrice price = priceService.findEffectivePrice(
                config.getTenantId(), config.getProvider(), config.getModelName());
            // 业务批4 Q4-A：官方价＋服务费模式=官方折算＋每次服务费（镜像 AiBillingCalculator#officialPlusFee）
            if (isOfficialPlusFee()) {
                return tokenCostOf(price, snapshot).add(fixedUnitPrice());
            }
            if (!officialPricing(price) || snapshot == null || !snapshot.hasTokenUsage()) {
                return fixedUnitPrice();
            }
            return tokenCostOf(price, snapshot);
        } catch (Exception e) {
            return fixedUnitPrice();
        }
    }

    /** 官方 token 折算（输入+输出，cached 折扣忽略）：入参不全时返回 0，由调用方决定兜底口径 */
    private BigDecimal tokenCostOf(DcsAiModelPrice price, AiTokenUsageSnapshot snapshot) {
        if (price == null || snapshot == null || !snapshot.hasTokenUsage()) {
            return BigDecimal.ZERO;
        }
        BigDecimal in = BigDecimal.valueOf(snapshot.promptTokens())
            .multiply(nvl(price.getInputPricePerMillion()))
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal out = BigDecimal.valueOf(snapshot.completionTokens())
            .multiply(nvl(price.getOutputPricePerMillion()))
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return in.add(out).setScale(6, RoundingMode.HALF_UP);
    }

    /** 业务批4 Q4-A：官方价＋服务费模式判定（与 AiBillingCalculator#isOfficialPlusFee 同源） */
    private boolean isOfficialPlusFee() {
        return com.smartdock.service.support.AiBillingCalculator.BILLING_MODE_OFFICIAL_PLUS_FEE
            .equals(config.getBillingMode());
    }

    /** 官方 token 计费模式 ∧ 有价行（与 AiBillingCalculator#shouldUseOfficial 的前两判同源；
     *  usage 判定属调用后真值, 估算路径（事前）按有 usage 假设走 token 口径） */
    private boolean officialPricing(DcsAiModelPrice price) {
        String mode = config.getBillingMode();
        if (mode == null || mode.isBlank()) {
            mode = "OFFICIAL_TOKEN";
        }
        return "OFFICIAL_TOKEN".equals(mode) && price != null;
    }

    /** AU-43：按次计费口径（镜像 AiBillingCalculator#resolveUnitPrice：config 单价 ≥0 优先, 缺省 0.01） */
    private BigDecimal fixedUnitPrice() {
        if (config.getUnitPricePerCall() != null && config.getUnitPricePerCall().compareTo(BigDecimal.ZERO) >= 0) {
            return config.getUnitPricePerCall();
        }
        return new BigDecimal("0.0100");
    }

    /**
     * 本笔未扣到钱(余额不足/非本币/零成本)时抬高该租户下回合门槛: 欠额≈估算成本×10、
     * 上限 1 元、TTL 7 天——余额充到门槛之上自动放行, 无需显式清除(glm53 P2-2 免费敞口闭环)。
     */
    private void raiseFloorIfUnpaid(String idempotencyKey, int promptChars) {
        try {
            if (walletService.deductedBizIdByIdempotency(idempotencyKey) != null) {
                return;
            }
            BigDecimal floor = estimateTurnCost(promptChars).multiply(BigDecimal.TEN).min(FLOOR_CAP)
                .max(MIN_TURN_BALANCE);
            RedisUtils.setCacheObject(FLOOR_KEY_PREFIX + config.getTenantId(), floor, FLOOR_TTL);
            log.warn("[AssistantBilling] 本笔未扣到钱, 抬高租户 {} 回合门槛至 {} 元(充值后自动恢复)",
                config.getTenantId(), floor.toPlainString());
        } catch (Exception e) {
            log.warn("[AssistantBilling] 地板价写入失败(不影响本轮): {}", e.getMessage());
        }
    }

    /**
     * 当前租户欠费地板价(无记录按 0 计)。
     */
    private BigDecimal currentFloor() {
        try {
            BigDecimal floor = RedisUtils.getCacheObject(FLOOR_KEY_PREFIX + config.getTenantId());
            return floor == null ? BigDecimal.ZERO : floor;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 业务批8 Q2：模型积分倍率（前置闸与落账两处共用，口径必须一致；NULL/非正数=1）。
     * 种子：免费×1 / flash×2 / 中档×2~3 / 旗舰×4~6。
     */
    private BigDecimal creditMultiplier(DcsAiConfig cfg) {
        BigDecimal m = cfg.getCreditMultiplier();
        return (m == null || m.signum() <= 0) ? BigDecimal.ONE : m;
    }

    /**
     * 按当前生效价估算本回合成本(prompt 字符×系数 + 输出下限 256)。
     * AU-43(1008)：无价行/按次模式同走保底价口径（与实扣一致），不再返回 0——
     * 「预估 0 实收 1 分」的坐地起价观感由此消除。
     */
    private BigDecimal estimateTurnCost(int promptChars) {
        try {
            DcsAiModelPrice price = priceService.findEffectivePrice(
                config.getTenantId(), config.getProvider(), config.getModelName());
            // 业务批4 Q4-A：官方价＋服务费——门槛估算也要含每次服务费（防「估 0 实收服务费」跳变；
            // 价格行缺失时官方部分为 0、只剩服务费，与实扣的 fee-only 兜底同口径）
            if (isOfficialPlusFee()) {
                return estimateTokenCost(price, promptChars).add(fixedUnitPrice());
            }
            if (!officialPricing(price)) {
                return fixedUnitPrice();
            }
            return estimateTokenCost(price, promptChars);
        } catch (Exception e) {
            return fixedUnitPrice();
        }
    }

    /** 按价行估算回合官方成本（prompt 字符×系数 + 输出下限 256）；无价行返回 0 */
    private BigDecimal estimateTokenCost(DcsAiModelPrice price, int promptChars) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        int promptTokens = Math.max(1, ceil(promptChars * CHARS_TO_TOKENS));
        BigDecimal in = BigDecimal.valueOf(promptTokens)
            .multiply(nvl(price.getInputPricePerMillion()))
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal out = BigDecimal.valueOf(EST_OUTPUT_FLOOR)
            .multiply(nvl(price.getOutputPricePerMillion()))
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return in.add(out).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 统计输入消息的文本字符数(估算 prompt token 用, 工具 schema 不计——模型侧另有系统开销已被兜底下限覆盖)。
     */
    private static int countPromptChars(List<Msg> messages) {
        if (messages == null) {
            return 0;
        }
        int chars = 0;
        for (Msg msg : messages) {
            for (TextBlock block : msg.getContentBlocks(TextBlock.class)) {
                chars += block.getText() == null ? 0 : block.getText().length();
            }
        }
        return chars;
    }

    private static int ceil(double v) {
        return BigDecimal.valueOf(v).setScale(0, RoundingMode.CEILING).intValueExact();
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
