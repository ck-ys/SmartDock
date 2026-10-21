package org.dromara.assistant.persist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.core.AssistantRunContext;
import org.dromara.assistant.domain.DcsAssistantConversation;
import org.dromara.assistant.domain.DcsAssistantRun;
import org.dromara.assistant.limiter.AssistantDailyQuota;
import org.dromara.assistant.security.AssistantPiiFilter;
import org.dromara.assistant.service.AssistantConversationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流侧会话记录器（批G G2，2026-10-06）：挂 AGUI enricher，按 run 累积 assistant
 * 文本，TEXT_MESSAGE_END 落库、RUN_FINISHED/ERROR 回写 run 终态。
 *
 * <p>批J-4（2026-10-06）：同步收集 ToolCall 事件为工具步骤（name/args/brief/status），
 * 随 assistant 消息落 tool_steps 列——历史回放恢复回合 stat/步骤行。ReAct 时序上
 * 工具先于终答文本，TEXT_MESSAGE_END 落库时步骤已齐；残余流(truncated)路径同样
 * 带快照。resultFull 不落库（体积/PII 面，前端回放留空）。</p>
 *
 * <p>Q5-A（2026-10-10 拍板）：同步收集 REASONING 思考段为 thinkSegs 随 tool_steps
 * 一起落库——刷新后回放恢复「已思考 · N 秒」可展开段。闸关租户内容帧在 enricher
 * 层已丢（落库跟随闸语义，只见 START/END 的空段=有耗时常无正文）；文本逐段 4000
 * 截断；落库前过 PII 掩码（异步侧 tenant 已解析处）。JSON 形态：有思考段时
 * {"steps":[...],"thinkSegs":[...]}，无则保持纯数组（旧行/前端双兼容，零迁移）；
 * steps 与 thinkSegs 各带 seq（同 thread 单调计数器）供前端时间线交错。</p>
 *
 * <p>身份口径：流线程无登录上下文——归属一律按 runId 回查 run 登记行（resolver 侧
 * 已绑定 tenant/user/thread），查不到=该 run 未登记（resolver 持久化失败降级路径），
 * 静默跳过不重试（§6.1 流式内容落库失败=可容忍损失，登记）。</p>
 *
 * <p>纪律：本组件一切异常自吞（WARN）——记录器绝不打断 SSE 主流；异步小池防反压
 * 到流线程；RunContext 逐批刷新给工具层供当前 runId（G3 幂等键）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantRunRecorder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AssistantConversationService conversationService;
    private final AssistantDailyQuota dailyQuota;
    /** 悬挂 interrupt 缓存（二站 T1）：interrupt 轮写入 / 干净终态清除——与框架 pending 严格镜像 */
    private final AssistantPendingInterruptCache pendingInterrupts;
    /** Q5-A：思考段落库前 PII 掩码（异步侧按 run 租户执行；测试夹具可传 null 跳过） */
    private final AssistantPiiFilter piiFilter;
    /** 批5 Q7-3：回答完成后发追问侧车（生成 3 条贴题追问异步回写消息行） */
    private final org.dromara.assistant.service.AssistantFollowupService followupService;

    /** 批B B1：末次模型调用 tokens 快照（上下文环数据源），终态 clearLastUsage */
    private final org.dromara.assistant.billing.AssistantCostLedger costLedger;

    /** 记录专用有界池（dspro P2-1 修：CallerRuns——终态写/refund 任务绝不能被静默丢弃，
     *  打满时借流线程执行，短阻塞优于丢终态） */
    private final ExecutorService pool = new ThreadPoolExecutor(
        1, 2, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(256),
        r -> {
            Thread t = new Thread(r, "dcs-asst-rec");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.CallerRunsPolicy());

    /** run 内消息缓冲（runId → messageId → 增量累积）；run 终态后移除。
     *  005 测试接缝：包内可见供单测直取播种（flushAndReap 的 merged 内容源）。 */
    final Map<String, Map<String, StringBuilder>> buffers = new ConcurrentHashMap<>();

    /** 会话级工具步骤缓冲（批J-4：threadId → toolCallId → 步骤累积）。
     *  键=threadId 而非 runId：HITL 确认流的 TOOL_CALL_START 建在中断 run、TOOL_CALL_RESULT
     *  落在 resume run——按 run 键控会让 resume 轮步骤恒空（E2E 实锤）；thread 键跨 run 续接，
     *  COMPLETED/FAILED 终态才清（INTERRUPTED 留给 resume 续用）。
     *  <p>批J 三审（dspro）钉死两条边界：①悬挂 interrupt（用户弃确认卡换会话）的键——
     *  二站 T1/T3 已根治（悬挂缓存+RetentionJob 30 分钟清扫配对回收，见 evictStepBuffers）；
     *  ②内层 LinkedHashMap 依赖「同 thread 单活跃 run」守卫（框架 AguiResumeCoordinator
     *  putIfAbsent 实证）——**仅单实例部署成立**，多实例 LB 需换并发结构。</p>
     *  内层 LinkedHashMap：enricher 回调固定在流线程串行，无需并发结构。 */
    private final Map<String, Map<String, ToolStepBuf>> stepBuffers = new ConcurrentHashMap<>();

    /** 思考段缓冲（Q5-A：threadId → 有序段列表）。键=threadId 同 stepBuffers（HITL resume
     *  跨 run 续接、终态清/INTERRUPTED 留），闸关租户只有 START/END 空段（正文帧在
     *  enricher 层已丢）。流线程串行回调，ArrayList 无需并发结构；包内可见供单测直取。 */
    final Map<String, List<ThinkSegBuf>> thinkBuffers = new ConcurrentHashMap<>();

    /** 时间线事件序（Q5-A：threadId → 单调计数器）——steps 与 thinkSegs 共用，
     *  落库后前端按 seq 交错恢复「思考①→工具→思考②」真实时序 */
    private final Map<String, AtomicInteger> orderCounters = new ConcurrentHashMap<>();
    /** 三审遗留 P1-2（dspro）：终态写中标记——终态 UPDATE 在异步池排队期间 lastEventAt
     *  已被清，零事件收割器会抢赢真终态（COMPLETED 被改写 client-disconnect+误退额度）；
     *  finishRun 提交前登记、写完撤岗，收割器见此键让行 */
    private final java.util.Set<String> terminalWritePending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ===== 批6-#2 刷新保全（2026-10-13）：增量落库 + 失联收割 =====
    // 根因：客户端断连（刷新/断网）后框架往死 socket 写帧抛 IOException → Reactor 取消
    // 整链 → agent 静默死亡（无终态事件）——interrupt-on-disconnect 开关管不到这条拆链。
    // 对策：生成途中周期冲刷 partial 行（刷新回来立刻看到已生成部分）；事件停滞超阈值
    // 判定失联，收割终局化（partial→truncated + run→INTERRUPTED/client-disconnect + 退回合）。

    // ===== 批6-#2 五张追踪表（005 测试接缝：包内可见供单测直取播种，见 thinkBuffers 先例） =====
    /** 每 run 末次事件时刻（收割判据：停滞超 STALL_MS=失联） */
    final Map<String, Long> lastEventAt = new ConcurrentHashMap<>();
    /** 每 run 的步骤缓冲键（冲刷/收割线程无 context，建侧在此登记） */
    final Map<String, String> runStepKeys = new ConcurrentHashMap<>();
    /** 每 run 的 partial 行引用（首插时锚定，终道/收割时清理；防重复插入） */
    final Map<String, PartialRef> partialRefs = new ConcurrentHashMap<>();
    /** 每 run 末次冲刷时刻（节流：FLUSH_MS 内不重复写库） */
    final Map<String, Long> lastPartialFlushAt = new ConcurrentHashMap<>();
    /** 每 run 末次冲刷时的内容长度（内容无变不空写） */
    final Map<String, Integer> lastPartialLen = new ConcurrentHashMap<>();

    // ===== 稳流① Q1（2026-10-20）：run 级诊断埋点——终局一行日志+终态计数（报表归稳流②） =====
    /** 每 run 首事件时刻（终局算时长；零事件 run 恒缺=建侧失败路径自有 WARN） */
    final Map<String, Long> runFirstEventAt = new ConcurrentHashMap<>();
    /** 每 run 事件间最大静默毫秒（心跳帧计入——看 maxGap 与 heartbeats 的组合判性质） */
    final Map<String, java.util.concurrent.atomic.AtomicLong> runMaxGapMs = new ConcurrentHashMap<>();
    /** 每 run 心跳帧数（0=该 run 心跳未生效——流链路排障第一指标） */
    final Map<String, java.util.concurrent.atomic.AtomicInteger> runHeartbeats = new ConcurrentHashMap<>();
    /** 每 run 末次业务事件时刻（glm53 审查 P2-1：心跳帧不计——「纯心跳零业务」持续超限
     *  = 服务端真挂起（工具/模型死锁但 interval 独立跳），须按停滞收割，不能被心跳无限喂养） */
    final Map<String, Long> lastBusinessEventAt = new ConcurrentHashMap<>();
    /** 纯心跳收割阈值：10 分钟零业务事件（合法静默=搜索 10-25s/压缩摘要分钟级，10min 只剩挂死） */
    private static final long PURE_HEARTBEAT_MS = 600_000L;
    /** 终态计数（租户 → status|reason → 次数；进程内累计，稳流②管理报表数据源。
     *  glm53 审查 P2-2：按租户分桶——原进程级聚合会把全平台流量下发给租户管理员，破多租户信息隔离口径 */
    private final Map<String, Map<String, java.util.concurrent.atomic.LongAdder>> terminalOutcomeCounts =
        new ConcurrentHashMap<>();

    /** 失联判定阈值：须覆盖合法事件静默——dspro P1-1 回修 40s→60s：联网搜索等长工具
     *  实测静默 10-25s（WebSearchTools 不走 withTimeout），40s 余量仅 1.6×，多连发搜索
     *  或网络劣化即误杀；60s=2.4× 余量，且在飞标记只保护模型调用段（工具/收尾段裸奔） */
    private static final long STALL_MS = 60_000L;
    /** 存活 run 的 partial 冲刷节流间隔 */
    private static final long FLUSH_MS = 5_000L;

    /** 005 测试接缝（2026-10-14）：时钟可注入——单测拨针驱动 STALL/FLUSH 判定，不睡墙钟。
     *  默认系统时钟；非构造注入（Spring 无 Clock bean），包内 setter 仅供单测。 */
    private Clock clock = Clock.systemDefaultZone();

    void setClockForTest(Clock clock) {
        this.clock = clock;
    }

    /** partial 行锚（messageId+归属快照：冲刷线程无 context，插入时锚定免每 tick 回查）。
     *  005 测试接缝：包内可见供单测播种（收割/节流用例需预置锚）。 */
    record PartialRef(Long messageId, String tenantId, Long conversationId, Long userId) {}

    private int nextOrder(String stepKey) {
        return orderCounters.computeIfAbsent(stepKey, k -> new AtomicInteger()).getAndIncrement();
    }

    /** 步骤累积体（args 上限 400 / brief 上限 120——与前端 AssistantStep 展示口径一致；
     *  argsFull 12000：悬挂卡重建专用全量副本，五需求批 R4a——表单卡（fields_json）参数
     *  远超 400，截断副本过不了前端 JSON.parse=换设备/刷新后卡片空底） */
    static final class ToolStepBuf {
        final String toolCallId;
        final String name;
        final int order;
        final StringBuilder args = new StringBuilder();
        final StringBuilder argsFull = new StringBuilder();
        String brief = "";
        String status = "run";

        ToolStepBuf(String toolCallId, String name, int order) {
            this.toolCallId = toolCallId;
            this.name = name;
            this.order = order;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", toolCallId);
            m.put("name", name);
            m.put("argsText", args.toString());
            m.put("brief", brief);
            // 批J 三审（glm53 P2-3）：落库快照不记录"运行中"——快照时刻仍 run 的步骤
            // = 被放弃的残留（正常流 TOOL_CALL_RESULT 先于 TEXT_MESSAGE_END），回放按 err 渲染
            m.put("status", "run".equals(status) ? "err" : status);
            m.put("seq", order);
            return m;
        }
    }

    /** 思考段累积体（Q5-A）：文本上限 4000（防超长思考撑爆落库列）；startMs/endMs 取
     *  事件自带 timestamp（epoch ms），缺省退系统钟——durMs 供回放「已思考 · N 秒」 */
    static final class ThinkSegBuf {
        final String messageId;
        final int order;
        final long startMs;
        final StringBuilder text = new StringBuilder();
        Long endMs;

        ThinkSegBuf(String messageId, int order, Long timestamp) {
            this.messageId = messageId;
            this.order = order;
            this.startMs = timestamp != null ? timestamp : System.currentTimeMillis();
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", messageId);
            m.put("text", text.toString());
            m.put("startMs", startMs);
            // 三席审查回修：未闭合段（流被掐断缺 END）按快照时刻计实耗——落 0 会让回放侧
            // 「Date.now()-startMs」兜底显示天文数字（前端已同步加 startedAt 兜底）
            m.put("durMs", endMs != null ? Math.max(0L, endMs - startMs)
                : Math.max(0L, System.currentTimeMillis() - startMs));
            m.put("seq", order);
            return m;
        }
    }

    /** 参数增量入步骤缓冲（args 上限 400，防超长 JSON 撑爆落库列；argsFull 12000 喂悬挂卡重建——三审回修 glm53 P2: 30 字段含长 options 的 fields_json 最坏 ~4KB+, 4000 仍会截断致 JSON.parse 失败空底） */
    private void appendArgs(String stepKey, String toolCallId, String delta) {
        Map<String, ToolStepBuf> steps = stepBuffers.get(stepKey);
        ToolStepBuf buf = steps == null ? null : steps.get(toolCallId);
        if (buf != null && delta != null) {
            if (buf.args.length() < 400) {
                buf.args.append(delta, 0, Math.min(delta.length(), 400 - buf.args.length()));
            }
            if (buf.argsFull.length() < 12000) {
                buf.argsFull.append(delta, 0, Math.min(delta.length(), 12000 - buf.argsFull.length()));
            }
        }
    }

    /** 思考增量入段（Q5-A）：优先匹配 messageId 的最后一个未闭合段（框架同段多帧），
     *  无匹配退最后一个未闭合段（messageId 缺省容错）；无未闭合段丢弃（先内容后 START 的乱序不产段） */
    private void appendThink(String stepKey, String messageId, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        List<ThinkSegBuf> segs = thinkBuffers.get(stepKey);
        if (segs == null || segs.isEmpty()) {
            return;
        }
        ThinkSegBuf target = null;
        for (int i = segs.size() - 1; i >= 0; i--) {
            ThinkSegBuf s = segs.get(i);
            if (s.endMs != null) {
                break;
            }
            if (messageId == null || messageId.equals(s.messageId)) {
                target = s;
                break;
            }
        }
        if (target != null && target.text.length() < 4000) {
            target.text.append(delta, 0, Math.min(delta.length(), 4000 - target.text.length()));
        }
    }

    /** 闭合思考段（Q5-A）：同 appendThink 的定位口径；timestamp 缺省退系统钟 */
    private void closeThink(String stepKey, String messageId, Long timestamp) {
        List<ThinkSegBuf> segs = thinkBuffers.get(stepKey);
        if (segs == null || segs.isEmpty()) {
            return;
        }
        for (int i = segs.size() - 1; i >= 0; i--) {
            ThinkSegBuf s = segs.get(i);
            if (s.endMs != null) {
                break;
            }
            if (messageId == null || messageId.equals(s.messageId)) {
                s.endMs = timestamp != null ? timestamp : System.currentTimeMillis();
                return;
            }
        }
    }

    /**
     * enricher 每批事件回调（同步轻扫+异步落库）。
     */
    public void onEvents(AguiStreamContext context, List<AguiEvent> events) {
        if (context == null || events == null || events.isEmpty()) {
            return;
        }
        String runId = context.getRunId();
        String threadId = context.getThreadId();
        if (runId == null) {
            return;
        }
        // 步骤缓冲键：thread 优先（HITL 中断→resume 跨 run 续接），退化 runId
        String stepKey = threadId != null ? threadId : runId;
        // 批6-#2：冲刷/收割线程无 context——run 侧键与时刻在建侧（流线程）登记；
        // 稳流① Q1：首事件时刻+事件间最大静默同刻埋点（终局诊断行数据源）。
        // glm53 审查 P2-1：本批含任何业务帧（非心跳）才刷 lastBusinessEventAt——
        // 纯心跳批只喂 lastEventAt（看门狗/合法静默豁免），喂不了「真挂起」判据
        boolean anyBusiness = events.stream().anyMatch(e ->
            !(e instanceof AguiEvent.Custom c
                && org.dromara.assistant.core.DcsHeartbeatMiddleware.HEARTBEAT_NAME.equals(c.name())));
        long nowMs = System.currentTimeMillis();
        Long prevEventAt = lastEventAt.put(runId, nowMs);
        runFirstEventAt.putIfAbsent(runId, nowMs);
        if (anyBusiness) {
            lastBusinessEventAt.put(runId, nowMs);
        }
        if (prevEventAt != null) {
            runMaxGapMs.computeIfAbsent(runId, k -> new java.util.concurrent.atomic.AtomicLong())
                .accumulateAndGet(nowMs - prevEventAt, Math::max);
        }
        runStepKeys.putIfAbsent(runId, stepKey);
        // G3：流线程刷新当前 run（工具执行若在同线程可读到当前 runId）
        AssistantRunContext.refresh(runId, threadId);
        try {
            for (AguiEvent event : events) {
                if (event == null) {
                    continue;
                }
                switch (event.getType()) {
                    case CUSTOM -> {
                        // 稳流① Q1：心跳帧计数（0=心跳未生效的 run——链路排障第一指标）
                        if (event instanceof AguiEvent.Custom c
                            && org.dromara.assistant.core.DcsHeartbeatMiddleware.HEARTBEAT_NAME.equals(c.name())) {
                            runHeartbeats.computeIfAbsent(runId, k -> new java.util.concurrent.atomic.AtomicInteger())
                                .incrementAndGet();
                        }
                    }
                    case TOOL_CALL_START -> {
                        if (event instanceof AguiEvent.ToolCallStart s) {
                            stepBuffers.computeIfAbsent(stepKey, k -> new LinkedHashMap<>())
                                .putIfAbsent(s.toolCallId(), new ToolStepBuf(s.toolCallId(), s.toolCallName(), nextOrder(stepKey)));
                        }
                    }
                    case REASONING_START, REASONING_MESSAGE_START -> {
                        // 两族帧型并存（javap + E2E 实证）：网关真实流走 REASONING_MESSAGE_START/END
                        // （前端 aguiClient 同款），REASONING_START/END 是另一路变体——只收一族=段永不开，
                        // CONTENT 全按"无未闭合段"丢弃（tool_steps 落库恒无 thinkSegs 的实锤根因）
                        String msgId = null;
                        Long ts = null;
                        if (event instanceof AguiEvent.ReasoningStart s) {
                            msgId = s.messageId();
                            ts = s.timestamp();
                        } else if (event instanceof AguiEvent.ReasoningMessageStart m) {
                            msgId = m.messageId();
                            ts = m.timestamp();
                        }
                        final String mid = msgId;
                        final Long stamp = ts;
                        List<ThinkSegBuf> segs = thinkBuffers.computeIfAbsent(stepKey, k -> new ArrayList<>());
                        // 三席审查 P2-7 防御：同 messageId 重复 START（重连/重放）不重开段不烧 seq
                        if (mid == null || segs.stream().noneMatch(b -> b.endMs == null && mid.equals(b.messageId))) {
                            segs.add(new ThinkSegBuf(mid, nextOrder(stepKey), stamp));
                        }
                    }
                    case REASONING_MESSAGE_CONTENT, REASONING_MESSAGE_CHUNK -> {
                        // 与 TOOL_CALL_CHUNK/ARGS 同理：Content 与 Chunk 是平级形态，两路都收增量
                        if (event instanceof AguiEvent.ReasoningMessageContent c) {
                            appendThink(stepKey, c.messageId(), c.delta());
                        } else if (event instanceof AguiEvent.ReasoningMessageChunk h) {
                            appendThink(stepKey, h.messageId(), h.delta());
                        }
                    }
                    case REASONING_END, REASONING_MESSAGE_END -> {
                        if (event instanceof AguiEvent.ReasoningEnd e) {
                            closeThink(stepKey, e.messageId(), e.timestamp());
                        } else if (event instanceof AguiEvent.ReasoningMessageEnd me) {
                            closeThink(stepKey, me.messageId(), me.timestamp());
                        }
                    }
                    case TOOL_CALL_CHUNK, TOOL_CALL_ARGS -> {
                        // 批J 三审（dspro）：ToolCallArgs 与 ToolCallChunk 是平级 record（javap 实证），
                        // 框架真实参数增量走 TOOL_CALL_ARGS——单 instanceof Chunk 会让 argsText 恒空（DB 实锤）
                        if (event instanceof AguiEvent.ToolCallChunk c) {
                            appendArgs(stepKey, c.toolCallId(), c.delta());
                        } else if (event instanceof AguiEvent.ToolCallArgs a) {
                            appendArgs(stepKey, a.toolCallId(), a.delta());
                        }
                    }
                    case TOOL_CALL_RESULT -> {
                        if (event instanceof AguiEvent.ToolCallResult r) {
                            Map<String, ToolStepBuf> steps = stepBuffers.get(stepKey);
                            ToolStepBuf buf = steps == null ? null : steps.get(r.toolCallId());
                            if (buf != null && r.content() != null) {
                                buf.status = stepStatus(r.content());
                                buf.brief = r.content().length() <= 120
                                    ? r.content() : r.content().substring(0, 120);
                            }
                        }
                    }
                    case TEXT_MESSAGE_CONTENT -> {
                        if (event instanceof AguiEvent.TextMessageContent c) {
                            buffers.computeIfAbsent(runId, k -> new ConcurrentHashMap<>())
                                .computeIfAbsent(c.messageId(), k -> new StringBuilder())
                                .append(c.delta() == null ? "" : c.delta());
                        }
                    }
                    case TEXT_MESSAGE_END -> {
                        if (event instanceof AguiEvent.TextMessageEnd e) {
                            Map<String, StringBuilder> runBuffers = buffers.get(runId);
                            StringBuilder buf = runBuffers == null ? null : runBuffers.remove(e.messageId());
                            if (buf != null && !buf.isEmpty()) {
                                String full = buf.toString();
                                String model = safeModel(context);
                                // 步骤快照在事件线程取（异步落库前）——终态清缓冲不致丢步骤
                                String stepsJson = snapshotSteps(stepKey);
                                // 批B B1 修（glm53 P1-2/P2-1）：事件线程先记当前快照 before，
                                // 异步落库线程 awaitLastUsageChanged 等到「值变化」——E2E 实证
                                // 计费 record() 在 TEXT_MESSAGE_END 后 ~1ms 才落 map（雪花 ID
                                // 同毫秒序列+1），多子调用轮此刻 map 里是倒数第二次调用的旧值，
                                // 旧版「非 null 即快路径」会拿错一轮；before=null（单调用轮）
                                // 退化为等值出现。超时返回当前值 best-effort 落列。
                                // finally 走 clearLastUsageIf 条件清除——无条件 remove 会抹掉
                                // 下一文本消息刚写入的新值
                                Long before = costLedger.lastUsageOf(runId);
                                // 批6-#2：真实段落行落库前撤本 run 的 partial 行（同步移锚防
                                // 冲刷线程重建；行删除随落库任务进池——流线程不做 DB 写）
                                final PartialRef partialRef = partialRefs.remove(runId);
                                CompletableFuture.runAsync(() -> {
                                    Long lastTokens = costLedger.awaitLastUsageChanged(runId, before, 1500L);
                                    try {
                                        persistAssistantMessage(runId, full, model, "stop", stepsJson, lastTokens);
                                    } finally {
                                        costLedger.clearLastUsageIf(runId, lastTokens);
                                    }
                                    // dspro P0-1①回修：先落权威行再删保底行——原「先删后插」
                                    // 之间刷新=整回合空白；换序后删除失败仅残留 partial 行
                                    // （deletePartial 带 end_reason='partial' 条件，收割器已转
                                    // truncated 时删 0 行天然幂等），内容不丢
                                    if (partialRef != null) {
                                        try {
                                            conversationService.deletePartialMessage(partialRef.tenantId(),
                                                partialRef.messageId());
                                        } catch (Exception ex) {
                                            log.warn("[AssistantRecorder] partial 行清理失败(残留行将由收割/保留期兜底): {}",
                                                ex.getMessage());
                                        }
                                    }
                                }, pool)
                                    .whenComplete((v, ex) -> {
                                        if (ex != null) {
                                            log.warn("[AssistantRecorder] assistant 消息落库失败 runId={}: {}", runId, ex.getMessage());
                                        }
                                    });
                            }
                        }
                    }
                    case RUN_FINISHED -> {
                        // outcome 语义（dsflash P1-2 修）：interrupt 轮=HITL 确认卡暂停，
                        // 记 INTERRUPTED 供 X-09/E5 断线重连数据源，不得记 COMPLETED
                        if (event instanceof AguiEvent.RunFinished fin
                            && fin.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome outcome) {
                            finishRun(runId, "INTERRUPTED", "interrupt", stepKey);
                            // 三席审查 P2-4：interrupts 空表/全无 id 时悬挂缓存不建条目 →
                            // RetentionJob 看不见该 thread，步骤/思考缓冲永不回收且污染同
                            // thread 下一轮快照——未登记即按终态直接清
                            if (!recordPendingInterrupts(threadId, outcome.interrupts())) {
                                evictStepBuffers(List.of(stepKey));
                            }
                        } else {
                            finishRun(runId, "COMPLETED", "normal", stepKey);
                            // 二站 T1 清除①：resume 轮终态（用户确认/取消/入口自动解锁三路同口）
                            // =框架已消费全部悬挂，清缓存
                            pendingInterrupts.clear(threadId);
                        }
                        AssistantRunContext.clear();
                    }
                    case RUN_ERROR -> {
                        if (event instanceof AguiEvent.RunError err) {
                            finishRun(runId, "FAILED", err.message() == null ? "error" : err.message(), stepKey);
                            // 三审回修（P0-1，glm53+dspro 独立反汇编交叉坐实，javap 亲证）：
                            // 框架 AguiResumeCoordinator.trackPendingInterrupts 只在「无错误的
                            // RunFinished」才移除悬挂（sawError=true 即跳过 remove），RUN_ERROR
                            // 事件本身从不消费悬挂——任何错误都不得清缓存，否则 resume 全量键
                            // 单边丢失=thread 永久毒化（无自愈）。另：契约错误帧走
                            // contractErrorEvents 直发 SSE、不经 enricher，本分支根本看不到它。
                            log.warn("[AssistantRecorder] run 出错保留悬挂缓存 thread={} code={}",
                                threadId, err.code());
                        } else {
                            finishRun(runId, "FAILED", "error", stepKey);
                        }
                        AssistantRunContext.clear();
                    }
                    default -> {
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[AssistantRecorder] 事件处理异常(不影响主流) runId={}: {}", runId, e.getMessage());
        }
    }

    /**
     * 二站 T1 写入：interrupt 轮 RUN_FINISHED 时把框架 Interrupt 快照进悬挂缓存
     * （threadId 为空无法键控——框架悬挂键也是 threadId，此时无从注入，仅记日志）。
     */
    private boolean recordPendingInterrupts(String threadId, List<AguiEvent.Interrupt> interrupts) {
        if (threadId == null || interrupts == null || interrupts.isEmpty()) {
            return false;
        }
        long at = System.currentTimeMillis();
        Map<String, ToolStepBuf> steps = stepBuffers.get(threadId);
        List<AssistantPendingInterruptCache.PendingInterrupt> pending = new ArrayList<>(interrupts.size());
        for (AguiEvent.Interrupt i : interrupts) {
            if (i == null || i.id() == null) {
                continue;
            }
            // 二站-B：卡展示数据源从步骤缓冲补全（name 按展示口径；args 用 R4a 全量副本
            // argsFull——400 截断副本过不了前端 JSON.parse，表单卡换设备重建空底）
            ToolStepBuf buf = steps == null ? null : steps.get(i.toolCallId());
            pending.add(new AssistantPendingInterruptCache.PendingInterrupt(
                i.id(), i.toolCallId(), i.reason(), i.expiresAt(), at,
                buf == null ? "" : buf.name, buf == null ? "" : buf.argsFull.toString()));
        }
        if (pending.isEmpty()) {
            return false;
        }
        pendingInterrupts.store(threadId, pending);
        log.debug("[AssistantRecorder] thread 挂起 {} 个 interrupt 待解锁注入 threadId={}",
            pending.size(), threadId);
        return true;
    }

    /**
     * 二站 T3 配对清理：RetentionJob 清扫超龄悬挂缓存后，同步回收同 thread 的
     * stepBuffers 滞留键（INTERRUPTED 不走 finishRun 清除路径的兜底，dspro P2-2 收口）。
     */
    public void evictStepBuffers(Collection<String> threadIds) {
        if (threadIds == null) {
            return;
        }
        for (String key : threadIds) {
            stepBuffers.remove(key);
            thinkBuffers.remove(key);
            orderCounters.remove(key);
        }
    }

    private void finishRun(String runId, String status, String reason, String stepKey) {
        // 稳流① Q1：终局诊断（时长/最大静默/心跳数；终态计数改在 DB 写成功分支——
        // glm53 审查 P2-5：迟到终态（rows=0 被条件丢弃）不得与收割终态双计）
        final String outcome = status + "|" + (reason == null ? "-" : reason);
        // 三审回修（dspro P1-3/dsflash P2）：tenantOfRun 是同步 DB 查询，抖动异常若穿出
        // 会连坐吞掉 finishRun 全部终态动作（缓冲清除/终态写/悬挂卡写入）——诊断行宁可
        // 缺租户也不能断链
        String terminalTenant;
        try {
            terminalTenant = tenantOfRun(runId);
        } catch (Exception e) {
            terminalTenant = null;
            log.warn("[AssistantRecorder] 终态租户查询失败(诊断行缺租户) runId={}: {}", runId, e.getMessage());
        }
        logRunDiagnostics(runId, terminalTenant, outcome);
        // 批B B1 修：这里不再 clearLastUsage——RUN_FINISHED 与 TEXT_MESSAGE_END 仅差毫秒级，
        // 提前清会与异步落库线程的 awaitLastUsageChanged 轮询竞态（清完轮询恒 null=永远写不进 tokens）。
        // 清除移至落库任务 finally；未走落库的 run（中断/无终答文本）靠账本环形截断(512)兜底回收
        Map<String, StringBuilder> leftover = buffers.remove(runId);
        // 批6-#2：终道撤 partial 锚+清 run 侧跟踪（残留 partial 行随终道任务删除；
        // 残留追踪键同清，防收割线程对已终态 run 误判失联）
        final PartialRef partialRef = partialRefs.remove(runId);
        clearRunTracking(runId);
        final String mergedLeftover = leftover == null ? "" : leftover.values().stream()
            .reduce(new StringBuilder(), StringBuilder::append).toString();
        final boolean hasLeftover = !mergedLeftover.isEmpty();
        if (partialRef != null || hasLeftover) {
            // 流被掐断（无 END）：残余增量按 truncated 落库, 中断可判（X-11）；
            // partial 行无论有无残余都要删（空残余竞态下防孤儿 partial 行）
            String stepsJson = hasLeftover ? snapshotSteps(stepKey) : null;
            CompletableFuture.runAsync(() -> {
                // 三审回修（dspro P1-1）：与 TEXT_MESSAGE_END 路径同序——先落权威
                // truncated 行再删保底 partial 行；原「先删后插」在插入失败时唯一副本
                // 已删=已生成内容永久丢失，成功时也有毫秒级整回合空白窗
                if (hasLeftover) {
                    persistAssistantMessage(runId, mergedLeftover, null, "truncated", stepsJson);
                }
                if (partialRef != null) {
                    try {
                        conversationService.deletePartialMessage(partialRef.tenantId(), partialRef.messageId());
                    } catch (Exception ex) {
                        log.warn("[AssistantRecorder] 终道 partial 行清理失败: {}", ex.getMessage());
                    }
                }
            }, pool);
        }
        // INTERRUPTED 保留步骤/思考缓冲（resume 轮续用同 thread 键）；终态才清
        if (!"INTERRUPTED".equals(status)) {
            stepBuffers.remove(stepKey);
            thinkBuffers.remove(stepKey);
            orderCounters.remove(stepKey);
        }
        // dspro P1-2 修：end_reason 列宽 64，provider 长文案会 value too long 吞终态+连坐 refund
        String safeReason = reason == null ? null : (reason.length() > 64 ? reason.substring(0, 64) : reason);
        // P1-2：登记终态写中（收割器让行），异步块 finally 撤岗
        terminalWritePending.add(runId);
        CompletableFuture.runAsync(() -> {
            try {
            try {
                // dspro P0-1③回修：0 行=run 已被收割/兜底抢先终局化，迟到真终态被条件
                // 丢弃——WARN 留观测痕（否则线上完全不可见，误杀只能靠用户复盘）
                int rows = conversationService.finishRun(runId, status, safeReason);
                if (rows == 0) {
                    log.warn("[AssistantRecorder] 迟到终态 {} 被丢弃（run 已被收割器/兜底终局化）runId={}",
                        status, runId);
                } else {
                    // 稳流① Q1 + P2-5 回修：真终态写成功才计数（收割侧已计过的不重复）
                    countOutcome(tenantOfRun(runId), outcome);
                }
            } catch (Exception e) {
                log.warn("[AssistantRecorder] run 终态回写失败 runId={}: {}", runId, e.getMessage());
            }            // refund 独立 try（终态写失败不得连坐吞补偿）；仅 FAILED 退——INTERRUPTED
            // 等确认续跑属正常流转（dspro P1-1 附带：按 run 登记行的租户/人退当日键）。
            // 批6-#2 守卫升级：按「回读实际状态」退——finish 条件化(仅 RUNNING→X)后，
            // 被收割器已迁 INTERRUPTED 的 run 收到迟到 FAILED 终态时 UPDATE 落空，
            // 不得再退（收割侧已按 RETURNING 退过，防双退）
            try {
                if ("FAILED".equals(status)) {
                    DcsAssistantRun run = conversationService.runByRunId(runId);
                    if (run != null && "FAILED".equals(run.getStatus())) {
                        refundIfWallet(run.getTenantId(), run.getUserId());
                    }
                }
            } catch (Exception e) {
                log.warn("[AssistantRecorder] 失败回合补偿失败 runId={}: {}", runId, e.getMessage());
            }
            } finally {
                terminalWritePending.remove(runId);
            }
        }, pool);
    }

    /**
     * Q4/ISSUE-007①（2026-10-14）：工厂建会话失败的同步终局化通道。
     *
     * <p>背景（R9 javap 实证）：框架把建会话异常走 contractErrorEvents 直发 SSE、不经
     * enricher——resolver 已登记的 run 行（RUNNING）从此零事件；60s 收割器按 lastEventAt
     * 迭代而该键从未注册，对此类 run 天然失明，此前只能等 15min RetentionJob 兜底：期间
     * 用户端假"生成中"且额度被占。本方法由 {@code AssistantAgentFactory.createFromLogin}
     * 在抛回框架前同线程同步调用，保证终态先于/同帧于错误帧落库。</p>
     *
     * <p>防双退守卫与 {@link #finishRun} 尾部同款：终态写条件化（仅 RUNNING→FAILED 生效），
     * 退款按「回读实际状态」判——0 行（已被收割器/兜底抢先终局化）或回读非 FAILED 一律
     * 不退。run 行未登记（resolver 的 PG 闪断降级路径）时静默跳过，无账可终。流内错误
     * （RUN_ERROR 帧）不经过本方法，仍走既有终道。</p>
     */
    public void finalizeBuildFailure(String runId, String reason) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        String safeReason = reason == null ? null : (reason.length() > 64 ? reason.substring(0, 64) : reason);
        try {
            int rows = conversationService.finishRun(runId, "FAILED", safeReason);
            if (rows == 0) {
                log.warn("[AssistantRecorder] 建会话失败终态被丢弃（run 已被收割器/兜底终局化）runId={}", runId);
            }
        } catch (Exception e) {
            log.warn("[AssistantRecorder] 建会话失败终态回写失败 runId={}: {}", runId, e.getMessage());
        }
        try {
            DcsAssistantRun run = conversationService.runByRunId(runId);
            if (run != null && "FAILED".equals(run.getStatus())) {
                refundIfWallet(run.getTenantId(), run.getUserId());
            }
        } catch (Exception e) {
            log.warn("[AssistantRecorder] 建会话失败回合补偿失败 runId={}: {}", runId, e.getMessage());
        }
    }

    /** 批6-#2：run 侧跟踪键清理（终道/收割共用；stepBuffers 族仍按既有语义单独管） */
    private void clearRunTracking(String runId) {
        lastEventAt.remove(runId);
        runStepKeys.remove(runId);
        lastPartialFlushAt.remove(runId);
        lastPartialLen.remove(runId);
    }

    /**
     * 稳流① Q1（2026-10-20）：run 终局诊断——一行 INFO（时长/最大事件静默/心跳数）
     * + 终态计数。心跳=0 且 maxGap 大的 run 即「看门狗误判高危样本」；正常 run 心跳
     * 只在合法静默段出现。计数为进程内口径（重启归零），报表化归稳流②。
     *
     * <p><b>glm53 审查回修</b>：P2-2 计数按租户分桶（进程级聚合=把全平台流量下发给
     * 租户管理员，破多租户信息隔离口径——tenantId 由调用方传入，收割路径手头有、
     * 终道路径异步块现查）；P2-5 countOutcome 独立方法——迟到终态（DB 条件写 0 行）
     * 不得计数，由调用方在写成功分支单独调。</p>
     */
    private void logRunDiagnostics(String runId, String tenantId, String outcome) {
        Long first = runFirstEventAt.remove(runId);
        java.util.concurrent.atomic.AtomicLong gap = runMaxGapMs.remove(runId);
        java.util.concurrent.atomic.AtomicInteger hb = runHeartbeats.remove(runId);
        lastBusinessEventAt.remove(runId);
        if (first == null) {
            // 零事件 run（建侧失败路径）——建侧失败/兜底收割已有专门 WARN，无诊断行
            return;
        }
        log.info("[AssistantRecorder] run 终局诊断 runId={} tenant={} outcome={} durationMs={} maxEventGapMs={} heartbeats={}",
            runId, tenantId, outcome, clock.millis() - first,
            gap == null ? 0 : gap.get(), hb == null ? 0 : hb.get());
    }

    /** 终态计数（写成功分支才调——防迟到终态与收割终态双计，glm53 审查 P2-5） */
    private void countOutcome(String tenantId, String outcome) {
        String tenant = tenantId != null ? tenantId : "-";
        terminalOutcomeCounts.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(outcome, k -> new java.util.concurrent.atomic.LongAdder()).increment();
    }

    /** 终态分布快照（稳流②管理报表数据源；进程内累计；按登录租户过滤——P2-2） */
    public Map<String, Long> terminalOutcomeSnapshot(String tenantId) {
        Map<String, Long> out = new LinkedHashMap<>();
        Map<String, java.util.concurrent.atomic.LongAdder> bucket = terminalOutcomeCounts.get(tenantId);
        if (bucket != null) {
            bucket.forEach((k, v) -> out.put(k, v.sum()));
        }
        return out;
    }

    /**
     * 080/ISSUE-R13-3 修（2026-10-14）：清人时逐 thread 清悬挂确认卡缓存。
     * 背景：管理员 purgeUser（GDPR 清人）删四表+附件+agentscope，但悬挂 HITL 条目
     * （含 argsJson 全量入参）按 threadId 驻留内存直至进程重启——被清用户数据残留。
     * 在控制器层（purgeUser 事务提交后）调用：与 evictStepBuffers 同拍配对清理。
     */
    public void evictPendingInterrupts(java.util.Collection<String> threadIds) {
        if (threadIds == null || threadIds.isEmpty()) {
            return;
        }
        for (String threadId : threadIds) {
            pendingInterrupts.clear(threadId);
        }
        log.info("[AssistantRecorder] 清人配对清理：悬挂确认卡缓存 {} 个 thread", threadIds.size());
    }

    /**
     * 批6-#2 增量冲刷+失联收割（每 3s 一轮，@Scheduled 调度线程）：
     * <ul>
     *   <li>存活 run（事件停滞 &lt; STALL_MS）：内容有变且过 FLUSH_MS 节流 → upsert
     *       partial 行（含工具步骤+思考段快照）——刷新/断网回来立刻看到已生成部分；</li>
     *   <li>失联 run（停滞 ≥ STALL_MS，框架拆链后无终态事件）：partial 行转 truncated
     *       （无 partial 行但有残余文本则按 X-11 惯例补插 truncated），run 条件迁移
     *       INTERRUPTED/'client-disconnect' 并退当日回合（RETURNING 赢者通吃，与真实
     *       终态竞态天然防双退）。</li>
     * </ul>
     * 每 run 独立 try（异常下一 tick 重试）——冲刷线程绝不影响流线程（记录器纪律）。
     * StringBuilder 与流线程并发读写存在撕裂读可能：catch 后下一 tick 以最新内容重试，
     * 内容保全以终道路径为准，冲刷属 best-effort。
     */
    @Scheduled(fixedDelay = 3_000, initialDelay = 90_000)
    public void flushAndReap() {
        flushAndReap(clock.millis());
    }

    /** 005 测试接缝：包内可见+时刻外参——单测拨针驱动收割/节流判定，不睡墙钟 */
    void flushAndReap(long now) {
        for (Map.Entry<String, Long> entry : lastEventAt.entrySet()) {
            String runId = entry.getKey();
            try {
                boolean stalled = now - entry.getValue() >= STALL_MS;
                // glm53 审查 P2-1（纯心跳收割）：lastEventAt 被心跳无限喂养永不 stalled，
                // 服务端真挂起（工具/模型死锁）会被拖到 15min RetentionJob 兜底——
                // 「末次业务事件 ≥10min」即按停滞收割（在飞豁免照旧兜住模型合法静默）
                if (!stalled) {
                    Long lastBiz = lastBusinessEventAt.get(runId);
                    long bizRef = lastBiz != null ? lastBiz : runFirstEventAt.getOrDefault(runId, entry.getValue());
                    stalled = now - bizRef >= PURE_HEARTBEAT_MS;
                }
                Map<String, StringBuilder> runBuffers = buffers.get(runId);
                String merged = runBuffers == null ? "" : runBuffers.values().stream()
                    .reduce(new StringBuilder(), StringBuilder::append).toString();
                if (stalled) {
                    // 批6-#2：模型调用在飞=活 run（慢首 token/长生成/传输层重试期间事件
                    // 静默是常态）——E2E 实锤 40s 纯停滞时钟会误杀；键=stepKey(threadId
                    // 优先，与 BillingModelDecorator 构造注入同源)。断连拆链会取消模型流
                    // →doFinally 清零→收割放行，判别零误伤
                    if (costLedger.isInFlight(runStepKeys.get(runId))) {
                        continue;
                    }
                    reapStalled(runId, merged, now);
                } else {
                    // 业务批7 Q12：冲刷门槛从「正文非空」扩为「正文或思考/工具有内容」——
                    // 纯思考阶段正文恒空，旧门槛导致 partial 行永不建立，刷新即思考+答案全
                    // 蒸发（回放只剩用户气泡）。变更比对同步换成 正文+活动 联合指纹。
                    // dspro P2-4 回修：*31 线性组合可被「正文+1、活动-31」抵消 → 位移组合
                    //（两轴各自 <2^20，不可互相抵消）
                    int activity = snapshotActivityLen(runStepKeys.get(runId));
                    int fingerprint = q12Fingerprint(merged.length(), activity);
                    if ((!merged.isEmpty() || activity > 0)
                        && fingerprint != lastPartialLen.getOrDefault(runId, -1)
                        && now - lastPartialFlushAt.getOrDefault(runId, 0L) >= FLUSH_MS) {
                        flushPartial(runId, merged, now);
                    }
                }
            } catch (Exception ex) {
                log.warn("[AssistantRecorder] 冲刷/收割单 run 异常(下一 tick 重试) runId={}: {}", runId, ex.getMessage());
            }
        }
        reapZeroEventRuns();
    }

    /**
     * 业务批10 #2（2026-10-14）：零事件 run 兜底收割。
     *
     * <p>背景（本批库内实锤）：框架部分失败路径（网关侧同步异常走 contractErrorEvents
     * 直发 SSE 等）整轮不经 enricher——resolver 已登记的 RUNNING run 从未注册
     * lastEventAt，60s 停滞收割循环对此天然失明，此前只能等 15min closeStuck 兜底：
     * 期间会话列表持续"正在回答…"、消息区空白（实测两轮 +15.5min 才被收尸）。</p>
     *
     * <p>口径：按 create_time 捞 RUNNING 超时行，差集掉已被事件流追踪的（那些走既有
     * 停滞收割口径），在飞放行（慢首 token/传输层重试期同属零事件，isInFlight 键=
     * threadId 与建侧 stepKey 同源），其余按同款条件迁移 finalizeStalledRun
     * （RETURNING 防双退）+退回合。终态化后前端 3s 轮询即刻收敛，回合转
     * "没等到回答"（TurnBlock 空回答轮诚实化）。</p>
     */
    private void reapZeroEventRuns() {
        try {
            List<DcsAssistantRun> stale = conversationService.listRunningOlderThan((int) (STALL_MS / 1000));
            for (DcsAssistantRun run : stale) {
                String runId = run.getRunId();
                if (runId == null || lastEventAt.containsKey(runId) || terminalWritePending.contains(runId)) {
                    continue;
                }
                if (costLedger.isInFlight(run.getThreadId())) {
                    continue;
                }
                List<DcsAssistantRun> closed = conversationService.finalizeStalledRun(run.getTenantId(), runId);
                if (!closed.isEmpty()) {
                    refundIfWallet(run.getTenantId(), run.getUserId());
                    log.warn("[AssistantRecorder] 零事件兜底收割 runId={}（建行≥{}s 无任何事件=建/流侧同步失败路径）："
                        + "INTERRUPTED/client-disconnect，回合已退", runId, STALL_MS / 1000);
                    // 稳流① Q1：零事件路径也进终态计数（首事件恒缺，诊断行跳过）
                    logRunDiagnostics(runId, run.getTenantId(), "INTERRUPTED|zero-event");
                    countOutcome(run.getTenantId(), "INTERRUPTED|zero-event");
                }
            }
        } catch (Exception ex) {
            log.warn("[AssistantRecorder] 零事件兜底扫描失败(下一 tick 重试): {}", ex.getMessage());
        }
    }

    /** 存活 run 的 partial 行冲刷（首插锚定归属，其后原地更新） */
    private void flushPartial(String runId, String merged, long now) {
        PartialRef ref = partialRefs.get(runId);
        if (ref == null) {
            // m3 P1-5 回修：取锚落空时 run 可能已终道收尾（TEXT_MESSAGE_END 撤锚/
            // finishRun 清跟踪），此处再插 partial 行会成为无人删除的孤儿——
            // 二次门校验跟踪键仍在（非原子但显著缩窗，终道 remove 在事件线程先于此 tick）
            if (!lastEventAt.containsKey(runId)) {
                return;
            }
            DcsAssistantRun run = conversationService.runByRunId(runId);
            if (run == null) {
                return; // 未登记（resolver 降级路径）——无落库锚，收割时统一清键
            }
            DcsAssistantConversation conv = conversationService.conversationByThread(run.getTenantId(), run.getThreadId());
            if (conv == null) {
                return;
            }
            String stepsJson = maskThinkText(snapshotSteps(runStepKeys.get(runId)), run.getTenantId());
            Long id = conversationService.appendAssistantMessage(run.getTenantId(), conv.getId(), run.getUserId(),
                merged, null, "partial", stepsJson);
            // dspro P0-2 回修：插行的三次 DB 往返期间终道可能已清跟踪——此刻 put 会「复活」
            // 已终态 run 的锚且无人再删该行（孤儿 partial）。复查发现已被清理→自删刚插的行
            if (!lastEventAt.containsKey(runId)) {
                try {
                    conversationService.deletePartialMessage(run.getTenantId(), id);
                } catch (Exception ex) {
                    log.warn("[AssistantRecorder] 孤儿 partial 自清失败(保留期兜底): {}", ex.getMessage());
                }
                return;
            }
            partialRefs.put(runId, new PartialRef(id, run.getTenantId(), conv.getId(), run.getUserId()));
        } else {
            String stepsJson = maskThinkText(snapshotSteps(runStepKeys.get(runId)), ref.tenantId());
            conversationService.updatePartialMessage(ref.tenantId(), ref.messageId(), merged, stepsJson, "partial");
        }
        lastPartialFlushAt.put(runId, now);
        // 业务批7 Q12：长度→联合指纹（正文+思考/工具活动），思考增长也触发下次冲刷
        lastPartialLen.put(runId, q12Fingerprint(merged.length(), snapshotActivityLen(runStepKeys.get(runId))));
    }

    /** 业务批7 Q12 联合指纹：位移组合防「正文+1、活动-31」式抵消（dspro P2-4），两轴各 <2^20 */
    static int q12Fingerprint(int contentLen, int activityLen) {
        return (contentLen << 20) | (activityLen & 0xFFFFF);
    }

    /** 业务批7 Q12：思考/工具活动量（字符数和）——纯思考阶段的"有内容"判据 */
    private int snapshotActivityLen(String stepKey) {
        if (stepKey == null) {
            return 0;
        }
        int len = 0;
        Map<String, ToolStepBuf> steps = stepBuffers.get(stepKey);
        if (steps != null) {
            for (ToolStepBuf b : steps.values()) {
                len += b.args.length() + (b.brief == null ? 0 : b.brief.length());
            }
        }
        List<ThinkSegBuf> thinks = thinkBuffers.get(stepKey);
        if (thinks != null) {
            for (ThinkSegBuf t : thinks) {
                len += t.text.length();
            }
        }
        return len;
    }

    /**
     * 失联收割：内容终局化（partial→truncated / 补插 truncated）+ run 条件终态 + 退回合。
     * finalize 返回空 = 真实终态先赢（事件线程 finishRun 已接管 partial 行），此处静默。
     */
    private void reapStalled(String runId, String merged, long now) {
        // dspro P0-1②回修：tick 采样(停滞)到此处间隔了 tenantOfRun 等 DB 往返——期间终道
        // 事件若涌到，lastEventAt 已被刷新，本轮收割放弃交终道收口（防「收割抢赢→真终态
        // 被 finish 条件丢弃→永久 client-disconnect+白退回合+双行」）；在飞顺带二次复核。
        // 005：复核时刻用外参 now（拨针可测），与采样同刻——真实调度两刻度差仅毫秒级
        Long seen = lastEventAt.get(runId);
        if (seen == null || now - seen < STALL_MS) {
            return;
        }
        String stepKey = runStepKeys.get(runId);
        if (costLedger.isInFlight(stepKey)) {
            return;
        }
        String tenantId = tenantOfRun(runId);
        PartialRef ref = partialRefs.remove(runId);
        try {
            if (ref != null) {
                String stepsJson = maskThinkText(snapshotSteps(stepKey), ref.tenantId());
                // 竞态下缓冲已清（merged 空）：COALESCE 保留行内已冲刷内容，仅转 truncated
                conversationService.updatePartialMessage(ref.tenantId(), ref.messageId(),
                    merged.isEmpty() ? null : merged, stepsJson, "truncated");
            }
            // dspro P0-1②：无锚不补插——锚不在=终道刚摘走（stop 权威行已在落库路上，
            // 此处补插必双行）；正常路径「有内容必有锚」（首冲刷在内容到达后 ≤3s 的 tick
            // 完成），无锚有残余仅剩 resolver 降级路径（run 行不存在，补插同样写不进）
            java.util.List<DcsAssistantRun> closed = conversationService.finalizeStalledRun(tenantId, runId);
            if (!closed.isEmpty()) {
                DcsAssistantRun r = closed.get(0);
                refundIfWallet(r.getTenantId(), r.getUserId());
                log.warn("[AssistantRecorder] 失联收割 runId={}（事件停滞≥{}s=客户端断连拆链）："
                    + "INTERRUPTED/client-disconnect，回合已退", runId, STALL_MS / 1000);
                // 稳流① Q1：收割终局也进诊断+计数（P2-2 租户分桶；P2-6 诊断行只在收割
                // 成功分支出——run 行已被别人终局化的迟到诊断无观测价值且三表已被对方清）
                logRunDiagnostics(runId, r.getTenantId(), "INTERRUPTED|client-disconnect");
                countOutcome(r.getTenantId(), "INTERRUPTED|client-disconnect");
            }
        } catch (Exception ex) {
            // dspro P1-2回修：失败保留追踪，下一 tick 重试（updatePartial/finalizeStalled
            // 均带条件写，天然可重入幂等）；DB 抖动不再等于永久 RUNNING/partial。
            // R2-F2/ISSUE-002 修（2026-10-14）：原版此处回填「伪造停滞时刻」覆盖流线程
            // 刚刷新的真实事件时刻——重试轮二次采样读到假停滞，活 run 被误收割（假中断
            // +白退+答案只剩尾部）。旧值在检查时点本就超阈值：保留不动即正确（终道刷新
            // 过=下一 tick 放行；仍停滞=下一 tick 照常收割），无需也不得回填。
            log.warn("[AssistantRecorder] 收割未完成(保留追踪, 下一 tick 重试) runId={}: {}",
                runId, ex.getMessage());
            if (ref != null) {
                partialRefs.putIfAbsent(runId, ref);
            }
            return;
        }
        buffers.remove(runId);
        clearRunTracking(runId);
        // dspro P2-5回修：client-disconnect 无确认卡可恢复——步骤/思考缓冲随收割清除，
        // 防同 thread 下一轮快照并入死轮残留；悬挂缓存仍有条目的 thread 除外（resume 语义）
        if (stepKey != null && (pendingInterrupts.peek(stepKey) == null
            || pendingInterrupts.peek(stepKey).isEmpty())) {
            evictStepBuffers(List.of(stepKey));
        }
    }

    /** 三审 P2（dspro）：credits 订阅租户建侧 tryTurn 本就跳过（resolver 109-111），
     *  终态/收割侧退款须同口径跳过——退了会误退当天早前钱包期扣的当日计数。
     *  查不到模式（纯函数单测无 Spring 上下文/订阅查询闪断）按钱包口径退，
     *  与 billingAwareLimits 的 fail-open 及既有行为一致。 */
    private void refundIfWallet(String tenantId, Long userId) {
        try {
            org.dromara.assistant.service.AssistantUserQuotaService quotaService =
                org.dromara.common.core.utils.SpringUtils.getBean(
                    org.dromara.assistant.service.AssistantUserQuotaService.class);
            if (quotaService.billingAwareLimits(tenantId, userId).creditsMode()) {
                log.debug("[AssistantRecorder] credits 订阅租户退款跳过 tenant={} user={}", tenantId, userId);
                return;
            }
        } catch (Exception ignore) {
            // 无上下文/闪断：按钱包口径退（fail-open 同款）
        }
        dailyQuota.refundTurn(tenantId, userId);
    }

    /** 收割路径取 run 租户（步骤掩码用；查不到返回 null 由掩码侧跳过） */
    private String tenantOfRun(String runId) {
        // 纯函数单测装配 service 为 null：诊断/计数跳过即可，不得连坐吞掉 finishRun
        // 的缓冲清除（60bb33e 起本方法被 finishRun 首行同步调用，无守卫时 NPE 先于
        // stepBuffers/thinkBuffers 清除抛出，终态缓冲滞留=Q5 单测实锤回归）
        if (conversationService == null) {
            return null;
        }
        DcsAssistantRun run = conversationService.runByRunId(runId);
        return run == null ? null : run.getTenantId();
    }

    private void persistAssistantMessage(String runId, String content, String model) {
        persistAssistantMessage(runId, content, model, "stop", null, null);
    }

    private void persistAssistantMessage(String runId, String content, String model, String endReason, String toolSteps) {
        persistAssistantMessage(runId, content, model, endReason, toolSteps, null);
    }

    /** 批B B1：totalTokens=本回合末次模型调用 input(+cached)+output（上下文环数据源，
     *  覆写口径非累计）；null（估算/缺 usage/截断路径）不写列=前端环空态 */
    private void persistAssistantMessage(String runId, String content, String model, String endReason,
                                         String toolSteps, Long totalTokens) {
        try {
            DcsAssistantRun run = conversationService.runByRunId(runId);
            if (run == null) {
                log.debug("[AssistantRecorder] run 未登记(resolver 侧降级), 跳过落库 runId={}", runId);
                return;
            }
            DcsAssistantConversation conv = conversationService.conversationByThread(run.getTenantId(), run.getThreadId());
            if (conv == null) {
                return;
            }
            Long messageId = conversationService.appendAssistantMessage(run.getTenantId(), conv.getId(),
                run.getUserId(), content, model, endReason, maskThinkText(toolSteps, run.getTenantId()), totalTokens);
            // 批5 Q7-3：正常完结的回合发追问侧车（失败/中断不值得追问）；侧车内部全吞异常。
            // question 在记录线程同步取（DESC LIMIT 1 毫秒级）——搬进侧车线程会把排队窗口内
            // 落库的「下一问」误当本回合问题（七席审查竞态推演）
            if (endReason == null || "stop".equals(endReason)) {
                followupService.tryStart(run.getTenantId(), run.getUserId(), messageId, run.getThreadId(),
                    conv.getTitle(),
                    conversationService.lastUserQuestion(run.getTenantId(), run.getUserId(), conv.getId()),
                    content);
            }
        } catch (Exception e) {
            log.warn("[AssistantRecorder] assistant 消息落库异常 runId={}: {}", runId, e.getMessage());
        }
    }

    /** Q5-A+goal 加固 H6：思考正文与工具步骤快照按 run 租户过 PII 掩码（快照线程无租户上下文，
     *  落库侧补）；H6 扩展——steps[].argsText/brief 此前裸入库，模型从记忆/上下文复述拼出的
     *  PII（如手机号）走工具入参这条道无兜底。两种形态都处理：纯数组（旧形态）与
     *  {"steps":[...],"thinkSegs":[...]}（新形态）；掩码失败不阻塞落库（原文降级落库可接受）。包内可见供单测直取 */
    String maskThinkText(String toolSteps, String tenantId) {
        if (toolSteps == null || piiFilter == null) {
            return toolSteps;
        }
        try {
            JsonNode root = MAPPER.readTree(toolSteps);
            boolean changed = maskThinkSegs(root, tenantId);
            JsonNode stepsArr = root.isArray() ? root : root.get("steps");
            changed |= maskStepArgs(stepsArr, tenantId);
            return changed ? MAPPER.writeValueAsString(root) : toolSteps;
        } catch (Exception e) {
            log.warn("[AssistantRecorder] 快照掩码失败(原文落库) : {}", e.getMessage());
            return toolSteps;
        }
    }

    /** thinkSegs[].text 掩码；命中并改写返回 true（未变=免重序列化，保字节稳定） */
    private boolean maskThinkSegs(JsonNode root, String tenantId) {
        JsonNode segs = root.get("thinkSegs");
        if (segs == null || !segs.isArray()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode seg : segs) {
            JsonNode text = seg.get("text");
            if (text != null && !text.isNull() && !text.asText().isEmpty()) {
                String masked = piiFilter.mask(text.asText(), tenantId);
                if (!masked.equals(text.asText())) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) seg).put("text", masked);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** goal 加固 H6：steps[].argsText 与 brief（工具结果前 120 字）掩码；入参源出网已脱敏，
     *  这里兜的是模型复述路径；status/order 等机器字段不动 */
    private boolean maskStepArgs(JsonNode stepsArr, String tenantId) {
        if (stepsArr == null || !stepsArr.isArray()) {
            return false;
        }
        boolean changed = false;
        for (JsonNode step : stepsArr) {
            if (!(step instanceof com.fasterxml.jackson.databind.node.ObjectNode obj)) {
                continue;
            }
            for (String field : new String[] {"argsText", "brief"}) {
                JsonNode v = obj.get(field);
                if (v != null && !v.isNull() && !v.asText().isEmpty()) {
                    String masked = piiFilter.mask(v.asText(), tenantId);
                    if (!masked.equals(v.asText())) {
                        obj.put(field, masked);
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    /** 当前 thread 的工具步骤快照 JSON（批J-4 + Q5-A 扩展，键=stepKey=threadId 优先）；
     *  无思考段=纯数组（旧行形态），有思考段={"steps":[...],"thinkSegs":[...]}（前端双兼容）；
     *  空返回 null（落库列可空）。包内可见供单测直取。 */
    String snapshotSteps(String stepKey) {
        Map<String, ToolStepBuf> steps = stepBuffers.get(stepKey);
        List<ThinkSegBuf> thinks = thinkBuffers.get(stepKey);
        boolean hasSteps = steps != null && !steps.isEmpty();
        boolean hasThinks = thinks != null && !thinks.isEmpty();
        if (!hasSteps && !hasThinks) {
            return null;
        }
        try {
            if (!hasThinks) {
                List<Map<String, Object>> list = new ArrayList<>(steps.size());
                for (ToolStepBuf buf : steps.values()) {
                    list.add(buf.toMap());
                }
                return MAPPER.writeValueAsString(list);
            }
            Map<String, Object> obj = new LinkedHashMap<>();
            List<Map<String, Object>> stepList = new ArrayList<>(hasSteps ? steps.size() : 0);
            if (hasSteps) {
                for (ToolStepBuf buf : steps.values()) {
                    stepList.add(buf.toMap());
                }
            }
            obj.put("steps", stepList);
            List<Map<String, Object>> thinkList = new ArrayList<>(thinks.size());
            for (ThinkSegBuf seg : thinks) {
                thinkList.add(seg.toMap());
            }
            obj.put("thinkSegs", thinkList);
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[AssistantRecorder] 工具步骤序列化失败 stepKey={}: {}", stepKey, e.getMessage());
            return null;
        }
    }

    /** 工具结果失败判别（批J-4，对齐前端 resultFailed 契约：error 字段在场即失败；TIMEOUT 形状同走 error） */
    private static String stepStatus(String content) {
        try {
            JsonNode node = MAPPER.readTree(content);
            return node.hasNonNull("error") ? "err" : "ok";
        } catch (Exception e) {
            return "ok";
        }
    }

    /** 模型名从 run 输入侧不可得（上下文无模型元数据）——置空，登记（usage 侧已有模型计费记录） */
    private String safeModel(AguiStreamContext context) {
        return null;
    }
}
