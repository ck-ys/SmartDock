package org.dromara.assistant.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 原生结构化反问桥（题12 案1，2026-10-31 拍板）：模型在执行中调 {@code ask_user} 时，
 * 后端把问题卡推给前端、挂起等用户选（run 保持 RUNNING，心跳照发），答完把选择作为
 * 工具结果还给模型继续跑。
 *
 * <p>与 MCP elicitation 桥同构但独立：那边挂的是外部 server 的在飞请求、审计落
 * {@code dcs_mcp_call_log}；这边是内置工具，不需要 server 名，也不该污染外部调用审计。
 * 卡面数据走 CUSTOM {@code ask_user} 帧（enricher 取走），作答走独立端点，不进 HITL resume。</p>
 *
 * <p>边界（三席审查回修 2026-10-31）：单会话最多同时挂 2 张、单实例全局最多 32 张
 * （防一个用户开多会话把全平台反问关停；上限按 boundedElastic 工具线程池量级取，
 * 超限直接回绝，不让模型把 run 挂死）；等待 240 秒（必须小于纯心跳 10 分钟收割线）；
 * 超时/中断/关页面按「未作答」收尾，模型被明确告知没拿到答案。</p>
 */
@Slf4j
@Component
public class AssistantAskUserBridge {

    /** 单会话并发挂起上限 */
    private static final int MAX_PENDING_PER_THREAD = 2;
    /** 单实例全局挂起上限（工具线程池安全阀，非会话口径） */
    private static final int MAX_PENDING_GLOBAL = 32;
    /** 卡信号仓上限（run 中断未取走的卡防泄漏） */
    private static final int MAX_SIGNALS = 128;
    /** 等待上限（秒） */
    public static final int TIMEOUT_SECONDS = 240;
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
        final CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        volatile long expiresAtMs;

        Pending(String id, String tenantId, Long userId, String threadId) {
            this.id = id;
            this.tenantId = tenantId;
            this.userId = userId;
            this.threadId = threadId;
        }
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

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
        if (threadId == null || threadId.isBlank() || questions == null || questions.isEmpty()) {
            return Map.of("action", "cancelled", "answers", List.of());
        }
        sweepExpired();
        long sameThread = pending.values().stream().filter(p -> threadId.equals(p.threadId)).count();
        if (sameThread >= MAX_PENDING_PER_THREAD || pending.size() >= MAX_PENDING_GLOBAL) {
            log.warn("[AskUser] 挂起已满（同会话 {} / 全局 {}），本次反问直接回绝 thread={}",
                sameThread, pending.size(), threadId);
            return Map.of("action", "busy", "answers", List.of());
        }
        String id = UUID.randomUUID().toString();
        Pending p = new Pending(id, tenantId, userId, threadId);
        pending.put(id, p);
        long remainMs = TIMEOUT_SECONDS * 1000L;
        p.expiresAtMs = System.currentTimeMillis() + remainMs;
        submitSignal(threadId, cardPayload(id, questions, remainMs));
        Map<String, Object> result;
        try {
            result = p.future.get(TIMEOUT_SECONDS + 5L, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            completeAs(p, "timeout");
            result = p.future.join();
        } catch (InterruptedException e) {
            // 停止生成/实例关停：中断不是用户超时，按取消收尾并恢复中断标志
            Thread.currentThread().interrupt();
            completeAs(p, "cancelled");
            result = p.future.join();
        } catch (CancellationException e) {
            result = Map.of("action", "cancelled", "answers", List.of());
        } catch (ExecutionException e) {
            log.warn("[AskUser] 等待作答异常（按取消收尾）：{}", e.getMessage());
            result = Map.of("action", "cancelled", "answers", List.of());
        } finally {
            pending.remove(id, p);
            removeSignal(threadId, id);
        }
        return result;
    }

    /**
     * 作答端点入口：归属校验（租户/用户/线程三段与挂起快照比对）→ 完成 future。
     * 幂等：已答/不存在给人话错误；超时后才到的答案如实回「已失效」。
     * answers 按卡片同口径收紧（≤4 题、每题 ≤4 项、单项 ≤40 字、补充 ≤200 字）——
     * 前端正常路径本就在此范围内，收紧是防绕页面直调的纵深防御。
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
        String act = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!"answer".equals(act) && !"cancel".equals(act)) {
            return "action 必须是 answer / cancel 之一";
        }
        List<Map<String, Object>> safe = sanitizeAnswers(answers);
        if ("answer".equals(act) && safe.isEmpty()) {
            return "没有收到选择（请至少选一项或补充一句）";
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("action", "cancel".equals(act) ? "cancelled" : "answered");
        value.put("answers", "cancel".equals(act) ? List.of() : safe);
        synchronized (p) {
            if (p.future.isDone()) {
                return "这个反问已失效（可能已作答或已超时关闭）";
            }
            p.future.complete(value);
        }
        pending.remove(id, p);
        removeSignal(p.threadId, id);
        return null;
    }

    /** 停止生成/会话作废时按归属取消该线程所有挂起卡（工具线程立即收尾，不空等 240s） */
    public void cancelThread(String tenantId, Long userId, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        for (Pending p : pending.values()) {
            if (threadId.equals(p.threadId) && p.tenantId.equals(tenantId) && p.userId.equals(userId)) {
                if (completeAs(p, "cancelled")) {
                    pending.remove(p.id, p);
                    removeSignal(threadId, p.id);
                }
            }
        }
    }

    /** enricher 取走该线程所有待出网的卡：剩余时长按真实到期时刻重算，已收尾的卡不再下发 */
    public List<Map<String, Object>> take(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        List<Map<String, Object>> list = signals.remove(threadId);
        if (list == null || list.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        List<Map<String, Object>> alive = new ArrayList<>(list.size());
        for (Map<String, Object> card : list) {
            Pending p = card.get("id") == null ? null : pending.get(String.valueOf(card.get("id")));
            if (p == null) {
                continue; // 已超时/已作答但未及出网的死卡：不再推给前端
            }
            card.put("remainingMs", Math.max(0L, p.expiresAtMs - now));
            alive.add(card);
        }
        return alive.isEmpty() ? null : alive;
    }

    /** 卡载荷（纯函数，单测直测）：只下发前端渲染需要的字段 */
    public static Map<String, Object> cardPayload(String id, List<Map<String, Object>> questions, long remainingMs) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("questions", questions == null ? List.of() : questions);
        v.put("remainingMs", Math.max(0L, remainingMs));
        return v;
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

    private boolean completeAs(Pending p, String action) {
        synchronized (p) {
            return p.future.complete(Map.of("action", action, "answers", List.of()));
        }
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

    /** 懒清扫：过期未答 → 按 timeout 完成并移除 */
    private void sweepExpired() {
        long now = System.currentTimeMillis();
        for (Pending p : pending.values()) {
            if (p.expiresAtMs > 0 && p.expiresAtMs <= now && !p.future.isDone()) {
                if (completeAs(p, "timeout")) {
                    pending.remove(p.id, p);
                    removeSignal(p.threadId, p.id);
                }
            }
        }
    }
}
