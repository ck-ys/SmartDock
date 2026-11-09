package org.dromara.assistant.core;

import io.agentscope.core.agui.event.AguiEvent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.persist.AssistantRunRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 原生结构化反问桥（题12 案1，2026-10-31 拍板）：模型在执行中调 {@code ask_user} 时，
 * 后端把问题卡推给前端、挂起等用户选（run 保持 RUNNING，心跳照发），答完把选择作为
 * 工具结果还给模型继续跑。
 *
 * <p>2026-11-07「反问卡做成 ZCode 一模一样」全量对齐（板：Q0 甲 / Q1 甲 / Q2 乙 /
 * Q3 甲 / Q4 甲 / Q5 甲 / Q6 甲）：</p>
 * <ul>
 *   <li><b>计时</b>：到 300 秒按空答案自动继续（关掉设置则走 480 秒 timeout）；倒计时只在
 *       最后 60 秒出现，是前端按钮，展示由卡载荷的绝对到期时刻决定。</li>
 *   <li><b>停表</b>：首次有效交互（悬停/点选/改自填/翻页/点倒计时）永久 snooze，
 *       不再自动收尾；另设 60 分钟安全阀，防用户走开后 run 变僵尸。</li>
 *   <li><b>队列</b>：同一 thread 多张卡排队，只有队首计时/下发；前一张答完，后一张才
 *       激活并重新从 300 秒起算（ZCode interaction-registry activateHead 同口径）。</li>
 *   <li><b>刷新</b>：卡帧带 {@code deadlineAt} 绝对到期时刻 + {@code snoozed}；前端刷新后
 *       查 pending 端点续走，不再回放旧帧里的相对时长。</li>
 *   <li><b>即时出卡</b>：桥知道当前 thread 对应的 runId 时，直接把 CUSTOM ask_user 帧
 *       喂进 run 事件汇，不再等下一批事件/20 秒心跳。</li>
 *   <li><b>无交互</b>：自动化只读装配不注册 ask_user（ToolRegistry gate），桥侧 thread
 *       为空也直接拒绝，不让无人值守的 run 白等。</li>
 * </ul>
 *
 * <p>与 MCP elicitation 桥同构但独立：那边挂的是外部 server 的在飞请求、审计落
 * {@code dcs_mcp_call_log}；这边是内置工具，不需要 server 名，也不该污染外部调用审计。
 * 卡面数据走 CUSTOM {@code ask_user} 帧，作答走独立端点，不进 HITL resume。</p>
 *
 * <p>边界：单会话最多同时挂 2 张、单实例全局最多 32 张（防一个用户开多会话把全平台反问
 * 关停；上限按 boundedElastic 工具线程池量级取，超限直接回绝，不让模型把 run 挂死）。
 * 超时/中断/关页面按「未作答」收尾，模型被明确告知没拿到答案。</p>
 */
@Slf4j
@Component
public class AssistantAskUserBridge {

    /** 单会话并发挂起上限（含排队卡） */
    private static final int MAX_PENDING_PER_THREAD = 2;
    /** 单实例全局挂起上限（工具线程池安全阀，非会话口径） */
    private static final int MAX_PENDING_GLOBAL = 32;
    /** 卡信号仓上限（run 中断未取走的卡防泄漏） */
    private static final int MAX_SIGNALS = 128;
    /** 自动继续的等待上限（秒）：5 分钟，与 ZCode 同值（ASK_USER_QUESTION_AUTO_RESOLUTION_MS=300000） */
    public static final int AUTO_CONTINUE_SECONDS = 300;
    /** 关掉自动继续后的等待上限（秒）：480 秒，仍小于纯心跳 10 分钟收割线 */
    public static final int TIMEOUT_SECONDS = 480;
    /** 倒计时出现的最后窗口（秒）：ZCode ELICITATION_FINAL_MINUTE_MS=60_000（只在最后一分钟显示） */
    public static final int COUNTDOWN_WINDOW_SECONDS = 60;
    /** 停表后的安全阀（秒）：用户走开也不让 run 永久僵尸；ZCode 无此阀（我们主动加的） */
    public static final int SNOOZE_SAFETY_SECONDS = 3600;
    /** 单次反问的题数与选项上限（前端卡片按此渲染；作答载荷同口径收紧） */
    public static final int MAX_QUESTIONS = 4;
    public static final int MAX_OPTIONS = 4;
    public static final int MAX_LABEL_LEN = 40;
    public static final int MAX_OTHER_LEN = 200;

    static final class Pending {
        final String id;
        final String tenantId;
        final Long userId;
        final String threadId;
        /** 到期后是「空答案继续」还是「超时收尾」；建卡时定死，运行中改设置不影响已挂起的卡 */
        final boolean autoContinue;
        /** 卡面题目快照（pending 查询/队首激活时重建载荷用） */
        final List<Map<String, Object>> questions;
        final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        /** 绝对到期时刻；排队中为 0，激活时才赋值 */
        volatile long expiresAtMs;
        /** 是否队首（只有队首计时/下发） */
        volatile boolean head;
        /** 首次有效交互后永久停表 */
        volatile boolean snoozed;
        volatile long snoozedAtMs;

        Pending(String id, String tenantId, Long userId, String threadId, boolean autoContinue,
                List<Map<String, Object>> questions) {
            this.id = id;
            this.tenantId = tenantId;
            this.userId = userId;
            this.threadId = threadId;
            this.autoContinue = autoContinue;
            this.questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** thread → 排队卡（队首=当前展示/计时的卡） */
    private final Map<String, Deque<Pending>> queues = new ConcurrentHashMap<>();
    /** thread → 当前 runId（适配器工厂绑定，用于即时推帧/收割豁免） */
    private final Map<String, String> runIdByThread = new ConcurrentHashMap<>();

    /** 两档等待上限（秒）。生产走默认构造；带参构造给单测接缝（不必真等 5 分钟）。 */
    private final int autoContinueSeconds;
    private final int timeoutSeconds;
    private final int snoozeSafetySeconds;

    @Autowired(required = false)
    private AssistantRunStreamHub runStreamHub;
    @Autowired(required = false)
    private AssistantRunRecorder runRecorder;

    public AssistantAskUserBridge() {
        this(AUTO_CONTINUE_SECONDS, TIMEOUT_SECONDS, SNOOZE_SAFETY_SECONDS);
    }

    public AssistantAskUserBridge(int autoContinueSeconds, int timeoutSeconds) {
        this(autoContinueSeconds, timeoutSeconds, SNOOZE_SAFETY_SECONDS);
    }

    AssistantAskUserBridge(int autoContinueSeconds, int timeoutSeconds, int snoozeSafetySeconds) {
        this.autoContinueSeconds = Math.max(1, autoContinueSeconds);
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.snoozeSafetySeconds = Math.max(1, snoozeSafetySeconds);
    }

    /** 测试/装配接缝：生产由 Spring 字段注入。 */
    public void setRunStreamHub(AssistantRunStreamHub runStreamHub) {
        this.runStreamHub = runStreamHub;
    }

    public void setRunRecorder(AssistantRunRecorder runRecorder) {
        this.runRecorder = runRecorder;
    }

    private final Map<String, List<Map<String, Object>>> signals =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Map<String, Object>>> eldest) {
                if (size() > MAX_SIGNALS) {
                    log.warn("[AskUser] 卡信号仓超限（{}），淘汰最早线程的未取走卡", MAX_SIGNALS);
                    return true;
                }
                return false;
            }
        });

    /**
     * 发一张反问卡并等待作答（在工具执行线程里同步等）。
     *
     * @return action=answered/cancelled/timeout/busy，answers=[{header,question,picked:[...],other}]
     */
    public Map<String, Object> ask(String tenantId, Long userId, String threadId,
                                   List<Map<String, Object>> questions) {
        // 缺省开：旧前端/自动化跑批不带开关时按 ZCode 默认值走
        return ask(tenantId, userId, threadId, questions, true);
    }

    public Map<String, Object> ask(String tenantId, Long userId, String threadId,
                                   List<Map<String, Object>> questions, boolean autoContinue) {
        if (threadId == null || threadId.isBlank() || questions == null || questions.isEmpty()) {
            return Map.of("action", "cancelled", "answers", List.of());
        }
        sweepExpired();
        String id = UUID.randomUUID().toString();
        Pending p = new Pending(id, tenantId, userId, threadId, autoContinue, questions);
        Deque<Pending> queue = queues.computeIfAbsent(threadId, k -> new ConcurrentLinkedDeque<>());
        boolean activate = false;
        // 计数与入队同一把锁：并发 ask 不会一起挤过上限，也不会出现「第三张以为只有一张」
        synchronized (pending) {
            long sameThread = pending.values().stream().filter(x -> threadId.equals(x.threadId)).count();
            if (sameThread >= MAX_PENDING_PER_THREAD || pending.size() >= MAX_PENDING_GLOBAL) {
                log.warn("[AskUser] 挂起已满（同会话 {} / 全局 {}），本次反问直接回绝 thread={}",
                    sameThread, pending.size(), threadId);
                return Map.of("action", "busy", "answers", List.of());
            }
            pending.put(id, p);
            synchronized (queue) {
                queue.addLast(p);
                activate = queue.peekFirst() == p;
            }
        }
        if (activate) {
            activate(p);
        }
        return await(p);
    }

    /** 工具线程等待：排队卡每秒看一眼是否被提升；队首按绝对到期/停表安全阀收尾。 */
    private Map<String, Object> await(Pending p) {
        try {
            while (true) {
                long now = System.currentTimeMillis();
                if (p.head) {
                    long deadline = p.snoozed
                        ? p.snoozedAtMs + snoozeSafetySeconds * 1000L
                        : p.expiresAtMs;
                    if (deadline > 0 && now >= deadline) {
                        finish(p, completeOnExpiryValue(p));
                        break;
                    }
                }
                long waitMs = 1000L;
                try {
                    return p.future.get(Math.max(1L, waitMs), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    // 继续循环：排队卡可能刚被提升；队首可能刚被 snooze
                }
            }
            return p.future.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(p, cancelledValue());
            return p.future.join();
        } catch (CancellationException e) {
            return cancelledValue();
        } catch (ExecutionException e) {
            log.warn("[AskUser] 等待作答异常（按取消收尾）：{}", e.getMessage());
            return cancelledValue();
        }
    }

    /**
     * 作答端点入口：归属校验（租户/用户/线程三段与挂起快照比对）→ 完成 future。
     * 幂等：已答/不存在给人话错误；超时后才到的答案如实回「已失效」。
     * 允许空答案：用户点「继续/提交」但没选，按「未作答」收尾，模型走稳妥默认。
     */
    public String answer(String tenantId, Long userId, String threadId, String id,
                         String action, List<Map<String, Object>> answers) {
        sweepExpired();
        Pending p = id == null ? null : pending.get(id);
        if (p == null) {
            return "这个反问已失效（可能已作答或已超时关闭）";
        }
        if (!p.tenantId.equals(tenantId) || !p.userId.equals(userId)
            || threadId == null || !p.threadId.equals(threadId)) {
            return "这个反问不属于当前会话，无法作答";
        }
        if (!p.head) {
            return "这张反问还在排队，请先回答当前问题";
        }
        String act = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!"answer".equals(act) && !"cancel".equals(act)) {
            return "action 必须是 answer / cancel 之一";
        }
        List<Map<String, Object>> safe = sanitizeAnswers(answers);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("action", "cancel".equals(act) ? "cancelled" : "answered");
        value.put("answers", "cancel".equals(act) ? List.of() : safe);
        if (!finish(p, value)) {
            return "这个反问已失效（可能已作答或已超时关闭）";
        }
        return null;
    }

    /**
     * 首次有效交互：永久停表（ZCode snoozeAutoResolution 同义）。
     * 只允许队首卡；重复调用幂等。
     */
    public String snooze(String tenantId, Long userId, String threadId, String id) {
        sweepExpired();
        Pending p = id == null ? null : pending.get(id);
        if (p == null) {
            return "这个反问已失效（可能已作答或已超时关闭）";
        }
        if (!p.tenantId.equals(tenantId) || !p.userId.equals(userId)
            || threadId == null || !p.threadId.equals(threadId)) {
            return "这个反问不属于当前会话，无法操作";
        }
        if (!p.head) {
            return "这张反问还在排队，请先回答当前问题";
        }
        synchronized (p) {
            if (p.future.isDone()) {
                return "这个反问已失效（可能已作答或已超时关闭）";
            }
            if (!p.snoozed) {
                p.snoozed = true;
                p.snoozedAtMs = System.currentTimeMillis();
            }
        }
        removeSignal(p.threadId, p.id);
        markRunWait(p, p.snoozedAtMs + snoozeSafetySeconds * 1000L);
        // 多标签页/多端：把「已停表」状态也推给其他还在看卡的端
        publishNow(p.threadId, cardPayload(p, System.currentTimeMillis()));
        return null;
    }

    /** 诊断/测试接缝：该 thread 当前挂起数（含排队），不暴露卡内容。 */
    public int pendingCount(String threadId) {
        if (threadId == null) {
            return 0;
        }
        return (int) pending.values().stream().filter(x -> threadId.equals(x.threadId)).count();
    }

    /** 刷新续走：返回该 thread 当前队首卡（含绝对到期时刻/停表状态），无卡返回 null。 */
    public Map<String, Object> pendingFor(String tenantId, Long userId, String threadId) {
        sweepExpired();
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        Pending p = head(threadId);
        if (p == null || !p.tenantId.equals(tenantId) || !p.userId.equals(userId)) {
            return null;
        }
        return cardPayload(p, System.currentTimeMillis());
    }

    /** 停止生成/会话作废时按归属取消该线程所有挂起卡（工具线程立即收尾，不空等） */
    public void cancelThread(String tenantId, Long userId, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        for (Pending p : new ArrayList<>(pending.values())) {
            if (threadId.equals(p.threadId) && p.tenantId.equals(tenantId) && p.userId.equals(userId)) {
                finish(p, cancelledValue());
            }
        }
    }

    /** 适配器工厂绑定当前 thread → runId（即时推帧/收割豁免的键）。 */
    public void bindRun(String threadId, String runId) {
        if (threadId != null && !threadId.isBlank() && runId != null && !runId.isBlank()) {
            runIdByThread.put(threadId, runId);
            Pending p = head(threadId);
            if (p != null) {
                markRunWait(p, p.snoozed
                    ? p.snoozedAtMs + snoozeSafetySeconds * 1000L
                    : p.expiresAtMs + 5000L);
            }
        }
    }

    public void unbindRun(String threadId, String runId) {
        if (threadId != null && runId != null) {
            runIdByThread.remove(threadId, runId);
            if (runRecorder != null) {
                runRecorder.clearInteractionWait(runId);
            }
        }
    }

    /** enricher 取走该线程待出网的卡：只下发队首，剩余时长按真实到期时刻重算。 */
    public List<Map<String, Object>> take(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        List<Map<String, Object>> list = signals.remove(threadId);
        if (list == null || list.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        List<Map<String, Object>> alive = new ArrayList<>(1);
        for (Map<String, Object> card : list) {
            Pending p = card.get("id") == null ? null : pending.get(String.valueOf(card.get("id")));
            if (p == null || !p.head || p.snoozed) {
                continue; // 已收尾/排队中/已停表的旧卡：不再推给前端
            }
            card.put("remainingMs", Math.max(0L, p.expiresAtMs - now));
            card.put("deadlineAt", p.expiresAtMs);
            alive.add(card);
            break; // 只下发队首
        }
        return alive.isEmpty() ? null : alive;
    }

    /** 卡载荷（纯函数，单测直测）：只下发前端渲染需要的字段。 */
    public static Map<String, Object> cardPayload(String id, List<Map<String, Object>> questions, long remainingMs) {
        return cardPayload(id, questions, remainingMs, 0L, false, true);
    }

    public static Map<String, Object> cardPayload(String id, List<Map<String, Object>> questions,
                                                  long remainingMs, long deadlineAt,
                                                  boolean snoozed, boolean autoContinue) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("questions", questions == null ? List.of() : questions);
        v.put("remainingMs", Math.max(0L, remainingMs));
        v.put("deadlineAt", Math.max(0L, deadlineAt));
        v.put("snoozed", snoozed);
        v.put("autoContinue", autoContinue);
        v.put("countdownWindowSeconds", COUNTDOWN_WINDOW_SECONDS);
        return v;
    }

    private static Map<String, Object> cardPayload(Pending p, long now) {
        long remaining = p.snoozed ? 0L : Math.max(0L, p.expiresAtMs - now);
        return cardPayload(p.id, p.questions, remaining,
            p.snoozed ? 0L : p.expiresAtMs, p.snoozed, p.autoContinue);
    }

    /** 作答载荷收紧（纯函数，单测直测）：只保留 {header,question,picked,other}，全空条目丢弃 */
    public static List<Map<String, Object>> sanitizeAnswers(List<Map<String, Object>> answers) {
        if (answers == null || answers.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(Math.min(answers.size(), MAX_QUESTIONS));
        for (Map<String, Object> a : answers) {
            if (a == null) {
                continue;
            }
            List<String> picked = new ArrayList<>();
            Object rawPicked = a.get("picked");
            if (rawPicked instanceof List<?> pl) {
                for (Object one : pl) {
                    String label = str(one, MAX_LABEL_LEN);
                    if (!label.isEmpty()) {
                        picked.add(label);
                    }
                    if (picked.size() >= MAX_OPTIONS) {
                        break;
                    }
                }
            }
            String other = str(a.get("other"), MAX_OTHER_LEN);
            if (picked.isEmpty() && other.isEmpty()) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("header", str(a.get("header"), 20));
            one.put("question", str(a.get("question"), 200));
            one.put("picked", picked);
            if (!other.isEmpty()) {
                one.put("other", other);
            }
            out.add(one);
            if (out.size() >= MAX_QUESTIONS) {
                break;
            }
        }
        return out;
    }

    private static String str(Object raw, int max) {
        if (raw == null) {
            return "";
        }
        String s = String.valueOf(raw).trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private void activate(Pending p) {
        long now = System.currentTimeMillis();
        p.head = true;
        p.expiresAtMs = now + (p.autoContinue ? autoContinueSeconds : timeoutSeconds) * 1000L;
        Map<String, Object> card = cardPayload(p, now);
        // Q4 甲：能定位到 run 就立即推帧；定位不到才退回「下一批事件带出」的旧兜底
        if (!publishNow(p.threadId, card)) {
            submitSignal(p.threadId, card);
        }
        markRunWait(p, p.expiresAtMs + 5000L);
    }

    private boolean finish(Pending p, Map<String, Object> value) {
        boolean done;
        synchronized (p) {
            done = p.future.complete(value);
        }
        if (!done) {
            return false;
        }
        pending.remove(p.id, p);
        removeSignal(p.threadId, p.id);
        Deque<Pending> queue = queues.get(p.threadId);
        Pending next = null;
        boolean empty = true;
        if (queue != null) {
            synchronized (queue) {
                queue.remove(p);
                next = queue.peekFirst();
                empty = queue.isEmpty();
                if (empty) {
                    queues.remove(p.threadId, queue);
                }
            }
        }
        if (empty) {
            clearRunWait(p);
        }
        if (next != null) {
            activate(next);
        }
        return true;
    }

    private void submitSignal(String threadId, Map<String, Object> payload) {
        synchronized (signals) {
            signals.computeIfAbsent(threadId, k -> new ArrayList<>()).add(payload);
        }
    }

    /** 收尾后定向清理未取走的卡信号（防「模型说超时、用户说没看到卡」） */
    private void removeSignal(String threadId, String id) {
        synchronized (signals) {
            List<Map<String, Object>> list = signals.get(threadId);
            if (list == null) {
                return;
            }
            list.removeIf(m -> id.equals(m.get("id")));
            if (list.isEmpty()) {
                signals.remove(threadId);
            }
        }
    }

    private Pending head(String threadId) {
        Deque<Pending> queue = queues.get(threadId);
        if (queue == null) {
            return null;
        }
        synchronized (queue) {
            return queue.peekFirst();
        }
    }

    /**
     * 到期收尾：自动继续=空答案（模型按稳妥默认往下走）；否则=timeout（模型改用正文追问）。
     * 两条都写明 answers 为空，前端与模型的口径一致。
     */
    private boolean completeOnExpiry(Pending p) {
        return finish(p, completeOnExpiryValue(p));
    }

    private static Map<String, Object> completeOnExpiryValue(Pending p) {
        return p.autoContinue
            ? Map.of("action", "answered", "answers", List.of(), "auto", true)
            : Map.of("action", "timeout", "answers", List.of());
    }

    private static Map<String, Object> cancelledValue() {
        return Map.of("action", "cancelled", "answers", List.of());
    }

    /** 懒清扫：队首过期/停表安全阀到点 → 按建卡时的口径收尾并提升下一张。 */
    private void sweepExpired() {
        long now = System.currentTimeMillis();
        for (Pending p : new ArrayList<>(pending.values())) {
            if (!p.head || p.future.isDone()) {
                continue;
            }
            long deadline = p.snoozed
                ? p.snoozedAtMs + snoozeSafetySeconds * 1000L
                : p.expiresAtMs;
            if (deadline > 0 && deadline <= now) {
                completeOnExpiry(p);
            }
        }
    }

    /** 即时推帧：桥里有 thread→runId 映射时，直接把卡写进 run 事件汇（刷新可重放）。 */
    private boolean publishNow(String threadId, Map<String, Object> card) {
        if (runStreamHub == null) {
            return false;
        }
        String runId = runIdByThread.get(threadId);
        if (runId == null || runId.isBlank()) {
            return false;
        }
        try {
            runStreamHub.accept(runId, List.of(
                new AguiEvent.Custom(threadId, runId, "ask_user", card)));
            return true;
        } catch (Exception e) {
            log.warn("[AskUser] 即时推帧失败（退回事件批兜底） thread={}: {}", threadId, e.getMessage());
            return false;
        }
    }

    private void markRunWait(Pending p, long untilMs) {
        if (runRecorder == null) {
            return;
        }
        String runId = runIdByThread.get(p.threadId);
        if (runId != null) {
            runRecorder.markInteractionWait(runId, untilMs);
        }
    }

    private void clearRunWait(Pending p) {
        if (runRecorder == null) {
            return;
        }
        String runId = runIdByThread.get(p.threadId);
        if (runId != null) {
            runRecorder.clearInteractionWait(runId);
        }
    }
}
