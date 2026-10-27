package org.dromara.assistant.core;

import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.event.AgentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.persist.AssistantRunRecorder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * AGUI 事件 enricher：思考内容洪流过滤（批A Task A3）+ 会话记录挂点（批G G2）。
 *
 * <p>背景：DeepSeek 推理模型思考期 13-20s 可产 3000-5000 帧
 * REASONING_MESSAGE_CONTENT——yml enable-reasoning=true 放数据到服务端（三层第一层），
 * 本件按 {@link AssistantReasoningGate}（第二层）过滤：</p>
 * <ul>
 *   <li>闸关：丢弃 REASONING_MESSAGE_CONTENT/CHUNK 内容帧（洪流源头），
 *       保留 REASONING_START/END + REASONING_MESSAGE_START/END 结构标记
 *       ——前端据此渲染"思考中"占位，不显示内容（用户拍板口径）；</li>
 *   <li>闸开：全放行，前端显示思考内容。</li>
 * </ul>
 * <p>TEXT、TOOL、RUN 等其余事件一律原样透传；批G 起每批事件先喂
 * {@link AssistantRunRecorder}（会话持久化+RunContext 刷新，自吞异常不打断主流）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantEventEnricher implements AguiEventEnricher {

    /** 闸关时要丢弃的内容帧类型（结构标记不在内，前端要靠它们显示"思考中"） */
    private static final Set<AguiEventType> REASONING_CONTENT_TYPES = Set.of(
        AguiEventType.REASONING_MESSAGE_CONTENT,
        AguiEventType.REASONING_MESSAGE_CHUNK
    );

    /** dcsToolInput 镜像用（toolInput JSON 字符串形态解析） */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private final AssistantReasoningGate gate;
    private final AssistantRunRecorder recorder;
    /** 回合成本累加器(批E E4): RUN_FINISHED 前取走, 发 cost_meta CUSTOM 帧给前端角标 */
    private final org.dromara.assistant.billing.AssistantCostLedger costLedger;
    /** 计划卡信号仓(二站③ AU-51): submit_plan 工具落计划, 本件取走转发 plan CUSTOM 帧 */
    private final AssistantPlanSignal planSignal;
    /** TODO 清单信号仓(ZCode 对齐 Q2, 批A): todo_write 工具落清单, 本件取走转发 todo CUSTOM 帧 */
    private final AssistantTodoSignal todoSignal;
    /** G1 乙类#2（#3104 事件源落地）：用户拒绝工具确认→动作账本 REJECTED 留痕 */
    private final org.dromara.assistant.service.AssistantActionService actionService;
    /** G3-A1：MCP elicitation 反问卡——桥里挂起的卡信号在本批事件前转发 CUSTOM 帧 */
    private final AssistantElicitationBridge elicitationBridge;
    /** B 批题5「续看」：进行中 run 事件广播汇——出网最终视图（闸后）喂汇，供刷新后 attach 重放 */
    private final AssistantRunStreamHub runStreamHub;

    @Override
    public List<AguiEvent> enrich(AgentEvent rawEvent, List<AguiEvent> events,
                                  io.agentscope.core.agui.adapter.strategy.AguiStreamContext context) {
        // G1 乙类#2：拒绝留痕（UserConfirmResultEvent=框架 #3104 官方事件源，含被拒
        // toolCall 与用户 reason；断连/超时不发此事件，天然区分拒绝与掉线）。
        // E2E 实证（2026-10-21）：本事件无 AGUI 转换器→events 恒空，必须放在
        // isEmpty 早退之前，否则拒绝轮永远静默跳过（audit 只依赖 rawEvent，不依赖出网帧）
        if (actionService != null
            && rawEvent instanceof io.agentscope.core.event.UserConfirmResultEvent ucr
            && context != null && context.getRunId() != null) {
            try {
                // 三审回修（三席共识）：归属锚从 thread 反查会话行改为 runId 反查 run 登记行
                // ——runId 全局唯一且登记时已做归属校验；thread 裸查在跨租户同 threadId
                // （超管切租户复用前端持久化 threadId）时会错挂租户，且无 thread 单列索引
                org.dromara.assistant.domain.DcsAssistantRun run = actionService.findRun(context.getRunId());
                if (run != null) {
                    for (io.agentscope.core.event.ConfirmResult cr : ucr.getConfirmResults()) {
                        if (cr.isConfirmed() || cr.getToolCall() == null) {
                            continue;
                        }
                        actionService.recordRejected(run.getTenantId(), run.getUserId(),
                            run.getRunId(), run.getThreadId(),
                            cr.getToolCall().getName(), cr.getToolCall().getId(), cr.getReason());
                    }
                }
            } catch (Exception e) {
                log.warn("[AssistantHitlAudit] 拒绝留痕失败（不连坐事件流）：{}", e.getMessage());
            }
        }
        if (events == null || events.isEmpty()) {
            return events;
        }
        // 批G G2：会话记录（先记录后过滤——过滤只影响出网，不影响落库完整性）；
        // recorder 可空守卫供纯过滤单测直构（生产由 Spring 注入恒非空）
        if (recorder != null) {
            recorder.onEvents(context, events);
        }
        List<AguiEvent> out = events;
        // 二站③ AU-51：submit_plan 落的计划信号在同线程下一批事件前转发——前端在
        // 回合进行中就亮出计划卡（TurnBlock.plan → PlanCard 三按钮），批首插入=先于
        // 本批业务事件到达前端；可空守卫供纯过滤单测直构（生产 Spring 注入恒非空）
        if (planSignal != null && context != null && context.getThreadId() != null) {
            AssistantPlanSignal.PlanPayload plan = planSignal.take(context.getThreadId());
            if (plan != null) {
                java.util.List<java.util.Map<String, String>> steps = new java.util.ArrayList<>();
                for (String s : plan.steps()) {
                    steps.add(java.util.Map.of("label", s, "state", "pending"));
                }
                java.util.Map<String, Object> value = new java.util.HashMap<>();
                if (plan.title() != null) {
                    value.put("title", plan.title());
                }
                value.put("steps", steps);
                out = new java.util.ArrayList<>(out);
                out.add(0, new AguiEvent.Custom(context.getThreadId(), context.getRunId(), "plan", value));
            }
        }
        // ZCode 对齐 Q2（批A）：todo_write 落的清单信号同线程下一批事件前转发——前端
        // StatPanel 回合进行中就更新任务清单分区；整表替换语义下同回合多次调用只发最新版
        if (todoSignal != null && context != null && context.getThreadId() != null) {
            java.util.List<AssistantTodoSignal.TodoItem> todo = todoSignal.take(context.getThreadId());
            if (todo != null) {
                java.util.List<java.util.Map<String, String>> items = new java.util.ArrayList<>();
                for (AssistantTodoSignal.TodoItem item : todo) {
                    items.add(java.util.Map.of(
                        "content", item.content(),
                        "status", item.status(),
                        "priority", item.priority()));
                }
                out = new java.util.ArrayList<>(out);
                out.add(0, new AguiEvent.Custom(context.getThreadId(), context.getRunId(), "todo",
                    java.util.Map.of("items", items)));
            }
        }
        // G3-A1：MCP elicitation 反问卡转发——server 反问时桥里挂起（tools/call 在飞），
        // 卡信号搭本批事件出网（工具静默期=心跳批 ≤20s 到卡，E2E 断言按此口径）。
        // 可能多张（多 server 同时反问），逐卡一帧；answer 走独立端点不走 resume
        if (elicitationBridge != null && context != null && context.getThreadId() != null) {
            java.util.List<java.util.Map<String, Object>> cards = elicitationBridge.take(context.getThreadId());
            if (cards != null && !cards.isEmpty()) {
                out = new java.util.ArrayList<>(out);
                // 逆序插头=保持桥的提交顺序（三审回修 P2：正序逐个 add(0) 会把多卡倒放）
                for (int i = cards.size() - 1; i >= 0; i--) {
                    out.add(0, new AguiEvent.Custom(context.getThreadId(), context.getRunId(),
                        "elicitation", cards.get(i)));
                }
            }
        }
        // CF-1（批I/J 补件，2026-10-06）：interrupt outcome 里每个 Interrupt 的 metadata 补
        // dcsToolInput=全字符串化工具入参。框架原样透出 toolInput（Long 直序列化成 JSON 数字）
        // ——19 位雪花 ID 在前端 JSON.parse 超 Number.MAX_SAFE_INTEGER 被取整（E2E 实证
        // topic_id …326→…300），确认卡删人回传 editedArgs 会催错科目。服务端 JVM Long 精确，
        // 这里逐值 String.valueOf 落镜像；Interrupt/RunFinished 是 record 不可变 → 整体重建
        out = enrichInterruptMetadata(out);
        // 批E E4：RUN_FINISHED 到达时，把本回合累计成本作为 cost_meta 帧插在最前
        // （前端气泡角标「本次 ¥x.xxxx · N tokens」，后端元值——前端禁自算价格）
        if (costLedger != null && context != null && context.getRunId() != null
            && events.stream().anyMatch(e -> e.getType() == AguiEventType.RUN_FINISHED)) {
            org.dromara.assistant.billing.AssistantCostLedger.Cost cost = costLedger.take(context.getRunId());
            if (cost != null) {
                java.util.Map<String, Object> value = java.util.Map.of(
                    "yuan", cost.yuan(),
                    "tokens", cost.tokens());
                out = new java.util.ArrayList<>(out);
                out.add(0, new AguiEvent.Custom(context.getThreadId(), context.getRunId(),
                    "cost_meta", value));
            }
        }
        // gate 可空守卫（纯逻辑单测直构 null=视为开闸透传；生产由 Spring 注入恒非空）
        List<AguiEvent> finalOut = out;
        if (gate != null && !gate.isEnabled()) {
            finalOut = out.stream()
                .filter(e -> !REASONING_CONTENT_TYPES.contains(e.getType()))
                .toList();
        }
        // B 批题5「续看」：出网最终视图喂广播汇（闸后=重挂重放的就是客户端本该看到的）；
        // RUN_FINISHED/RUN_ERROR 由汇侧 complete+定时逐出。喂汇失败不连坐主流（accept 内自吞）。
        if (runStreamHub != null && context != null && context.getRunId() != null) {
            try {
                runStreamHub.accept(context.getRunId(), finalOut);
            } catch (Exception e) {
                log.warn("[AssistantResume] run {} 事件入汇失败（续看降级轮询，不连坐主流）：{}",
                    context.getRunId(), e.getMessage());
            }
        }
        return finalOut;
    }

    /**
     * CF-1：RUN_FINISHED(interrupt outcome) 里每个 Interrupt 的 metadata 补 dcsToolInput。
     * 注意 Interrupt 是 outcome 载荷内的嵌套 record（不实现 AguiEvent，不单独出现在事件
     * 列表）——须重建 Interrupt 与 RunFinished。toolInput 两形态归一：Map（框架直放）与
     * JSON 字符串（resolveToolCallArgsJson）。失败自吞（镜像缺失时前端按不安全 id 降级）。
     */
    private List<AguiEvent> enrichInterruptMetadata(List<AguiEvent> events) {
        for (int i = 0; i < events.size(); i++) {
            AguiEvent e = events.get(i);
            if (!(e instanceof AguiEvent.RunFinished fin)
                || !(fin.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome oc)) {
                continue;
            }
            java.util.List<AguiEvent.Interrupt> patched = null;
            for (int j = 0; j < oc.interrupts().size(); j++) {
                AguiEvent.Interrupt intr = oc.interrupts().get(j);
                java.util.Map<String, Object> flat = flattenToolInput(intr.metadata());
                if (flat == null) {
                    continue;
                }
                if (patched == null) {
                    patched = new java.util.ArrayList<>(oc.interrupts());
                }
                java.util.Map<String, Object> newMd = new java.util.LinkedHashMap<>(intr.metadata());
                newMd.put("dcsToolInput", flat);
                patched.set(j, new AguiEvent.Interrupt(intr.id(), intr.reason(), intr.message(),
                    intr.toolCallId(), intr.responseSchema(), intr.expiresAt(), newMd));
            }
            if (patched != null) {
                java.util.List<AguiEvent> out = new java.util.ArrayList<>(events);
                out.set(i, new AguiEvent.RunFinished(fin.threadId(), fin.runId(), fin.result(),
                    new AguiEvent.RunFinishedInterruptOutcome(patched), fin.timestamp(), fin.rawEvent()));
                log.debug("[dcs-assistant] interrupt 入参字符串镜像已注入(dcsToolInput)");
                return out;
            }
        }
        return events;
    }

    /** toolInput → 精度安全镜像；无可镜像内容（无 metadata/toolInput/空）返回 null。
     * 批W3 三审定案（glm53/dspro/dsflash P0 链）：此前逐值 String.valueOf 全字符串化——
     * 19 位 Long 的精度保护是对的，但 Boolean/Integer 一并字符串化会让确认卡 editedArgs
     * 全量回传时框架参数绑定静默 null（"20" 绑不上 Integer，P0 T2 同族），act_audit 的
     * pass/版本参数被毁。口径：Boolean/安全域整数（|v|&lt;2^53 且无损）保留类型，
     * 超安全域数字与其余值仍字符串化。 */
    private java.util.Map<String, Object> flattenToolInput(java.util.Map<String, Object> md) {
        if (md == null || md.containsKey("dcsToolInput") || !md.containsKey("toolInput")) {
            return null;
        }
        try {
            java.util.Map<String, Object> flat = new java.util.LinkedHashMap<>();
            Object ti = md.get("toolInput");
            if (ti instanceof java.util.Map<?, ?> m) {
                for (java.util.Map.Entry<?, ?> en : m.entrySet()) {
                    flat.put(String.valueOf(en.getKey()), mirrorValue(en.getValue()));
                }
            } else if (ti instanceof String s && !s.isBlank()) {
                com.fasterxml.jackson.databind.JsonNode n = MAPPER.readTree(s);
                if (n.isObject()) {
                    java.util.Iterator<String> fields = n.fieldNames();
                    while (fields.hasNext()) {
                        String k = fields.next();
                        flat.put(k, mirrorJsonValue(n.get(k)));
                    }
                }
            }
            return flat.isEmpty() ? null : flat;
        } catch (Exception ex) {
            log.debug("[dcs-assistant] dcsToolInput 镜像失败(前端将降级通用卡): {}", ex.getMessage());
            return null;
        }
    }

    /** Map 形态值的镜像规则（见 {@link #flattenToolInput} 注释口径） */
    private static Object mirrorValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return v;
        }
        if (v instanceof java.lang.Number num) {
            return isJsSafeInteger(num) ? num : String.valueOf(v);
        }
        return String.valueOf(v);
    }

    /** JSON 形态值的镜像规则（同 {@link #mirrorValue}） */
    private static Object mirrorJsonValue(com.fasterxml.jackson.databind.JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber() && v.canConvertToLong() && Math.abs(v.longValue()) < 9007199254740992L) {
            return v.longValue();
        }
        return v.isValueNode() ? v.asText() : v.toString();
    }

    /** JS Number 安全整数域（2^53）且无小数——超出即会被前端取整，必须字符串化 */
    private static boolean isJsSafeInteger(java.lang.Number num) {
        double d = num.doubleValue();
        if (Math.floor(d) != d || Math.abs(d) >= 9007199254740992.0) {
            return false;
        }
        // 字符串长度兜底（科学计数等异常形态一律走字符串）
        return String.valueOf(num).length() <= 15;
    }
}
