package org.dromara.assistant.core;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自主权档位会话级暂存（题9 案1，2026-10-31 拍板）。
 *
 * <p>前端输入条常驻档位（每次/同类一次/全自动）经 AGUI {@code forwardedProps.autoLevel}
 * 逐请求上行；建 agent 时按本表决定写类工具的权限规则：</p>
 * <ul>
 *   <li>{@code always}（默认）：写类工具弹确认卡，行为与批W2 之前一致；</li>
 *   <li>{@code once}：本会话内确认过一次的同名工具，下一轮直接放行（按工具名记键）；</li>
 *   <li>{@code auto}：写类工具一律直接放行（审计仍有会话记录，提示文案显式告知）。</li>
 * </ul>
 *
 * <p>与 {@link AssistantWebSearchHolder} 同款「会话级暂存、下一请求生效」；
 * 档位翻转触发 agent 重建由 {@code needsRebuild} 判定（权限规则建时固化）。</p>
 */
public final class AssistantAutonomyHolder {

    public static final String ALWAYS = "always";
    public static final String ONCE = "once";
    public static final String AUTO = "auto";

    private static final Map<String, String> LEVEL = new ConcurrentHashMap<>();
    /** threadId → 本会话已确认过的工具名（once 档消费） */
    private static final Map<String, Set<String>> APPROVED = new ConcurrentHashMap<>();
    /** threadId → 批准代次（once 档：确认一次就要重算权限规则，代次变化=缓存 agent 失效） */
    private static final Map<String, Integer> APPROVED_GEN = new ConcurrentHashMap<>();
    /** threadId → 建 agent 时的档位基准（翻转触发缓存失效） */
    private static final Map<String, String> LAST_BUILT = new ConcurrentHashMap<>();
    /** threadId → 建 agent 时的批准代次（与 LAST_BUILT 配套，任一变化都重建） */
    private static final Map<String, Integer> BUILT_GEN = new ConcurrentHashMap<>();
    /**
     * threadId → 权限面变化待装标记（符合性审查 dspro P1，2026-10-31）。
     *
     * <p>框架 {@code loadOrCreateAgentStateForSlot} 对已有会话直接复用持久化 state——
     * 缓存 agent 重建后，建侧新算的权限规则进不了旧 state（auto→always 降级方向 fail-open）。
     * resolver 判定「权限面变化触发的重建」时置位，AgentFactory 建完 agent 消费：
     * 调官方 {@code replacePermissionContext} 把新规则装进该会话持久化 state。</p>
     */
    private static final Map<String, Boolean> RULE_INSTALL_PENDING = new ConcurrentHashMap<>();
    private static final int CAP = 50_000;

    /**
     * 跨实例共享后端（多实例批 1）：redis 模式读共享；dual/local 读本机。
     * 由 {@link AssistantAutonomySharedState} 在启动时绑定，纯单测不绑定=全本机。
     */
    public interface SharedBackend {
        boolean redisReadPreferred();

        Set<String> approved(String threadId);

        long approvedGen(String threadId);

        void approve(String threadId, String toolName);

        void markRuleInstallPending(String threadId);

        boolean consumeRuleInstallPending(String threadId);
    }

    private static volatile SharedBackend shared;

    public static void bindShared(SharedBackend backend) {
        shared = backend;
    }

    private static SharedBackend shared() {
        return shared;
    }

    private AssistantAutonomyHolder() {
    }

    private static void evictIfCrowded() {
        if (LEVEL.size() > CAP || LAST_BUILT.size() > CAP || APPROVED.size() > CAP
            || APPROVED_GEN.size() > CAP || BUILT_GEN.size() > CAP || RULE_INSTALL_PENDING.size() > CAP) {
            LEVEL.clear();
            LAST_BUILT.clear();
            APPROVED.clear();
            APPROVED_GEN.clear();
            BUILT_GEN.clear();
            RULE_INSTALL_PENDING.clear();
        }
    }

    /** 上行解析：只认 auto/once，其余（含 null/乱填）一律按默认 always——不能靠伪造参数放宽权限 */
    public static void set(String threadId, Object raw) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        evictIfCrowded();
        String v = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (AUTO.equals(v) || ONCE.equals(v)) {
            LEVEL.put(threadId, v);
        } else {
            LEVEL.remove(threadId);
        }
    }

    /** 当前档位（默认 always） */
    public static String level(String threadId) {
        if (threadId == null) {
            return ALWAYS;
        }
        return LEVEL.getOrDefault(threadId, ALWAYS);
    }

    /** 确认通过后记「同类已问过」（once 档下一轮直接放行）；代次自增触发缓存重建 */
    public static void rememberApproved(String threadId, String toolName) {
        if (threadId == null || threadId.isBlank() || toolName == null || toolName.isBlank()) {
            return;
        }
        evictIfCrowded();
        APPROVED.computeIfAbsent(threadId, k -> ConcurrentHashMap.newKeySet()).add(toolName);
        APPROVED_GEN.merge(threadId, 1, Integer::sum);
        SharedBackend backend = shared();
        if (backend != null) {
            try {
                backend.approve(threadId, toolName);
            } catch (Exception e) {
                // 共享写失败：本机已记；redis 模式下一次跨实例可能又问一次，但不 fail-open
                org.slf4j.LoggerFactory.getLogger(AssistantAutonomyHolder.class)
                    .warn("[autonomy] 共享批准写入失败 thread={} tool={}: {}", threadId, toolName, e.getMessage());
            }
        }
    }

    /** 本会话该工具是否已确认过 */
    public static boolean isApproved(String threadId, String toolName) {
        if (threadId == null || toolName == null) {
            return false;
        }
        SharedBackend backend = shared();
        if (backend != null && backend.redisReadPreferred()) {
            try {
                return backend.approved(threadId).contains(toolName);
            } catch (Exception e) {
                // 读不到共享批准=按未批准处理（最多再问一次，绝不误放行）
                return false;
            }
        }
        Set<String> set = APPROVED.get(threadId);
        return set != null && set.contains(toolName);
    }

    /**
     * 写类工具是否可绕过确认卡（建规则用）：
     * auto=全部放行；once=同名已确认过才放行；always=一律不放行。
     */
    public static boolean autoAllows(String threadId, String toolName) {
        String lv = level(threadId);
        if (AUTO.equals(lv)) {
            return true;
        }
        return ONCE.equals(lv) && isApproved(threadId, toolName);
    }

    /** AgentFactory 建 agent 后回写本次基准（档位 + 批准代次） */
    public static void markBuilt(String threadId, String levelAtBuild) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        evictIfCrowded();
        LAST_BUILT.put(threadId, levelAtBuild == null ? ALWAYS : levelAtBuild);
        SharedBackend backend = shared();
        long gen = APPROVED_GEN.getOrDefault(threadId, 0);
        if (backend != null && backend.redisReadPreferred()) {
            try {
                gen = backend.approvedGen(threadId);
            } catch (Exception e) {
                // 读不到就按本机基准；下一次请求 needsRebuild 会再判
                gen = APPROVED_GEN.getOrDefault(threadId, 0);
            }
        }
        BUILT_GEN.put(threadId, (int) Math.min(Integer.MAX_VALUE, gen));
    }

    /**
     * 档位或批准集合与建 agent 时不同 → 缓存 agent 需重建（权限规则固化在会话 state 里）。
     * once 档的关键：用户确认一次后 APPROVED 变化而档位不变，若不重建，规则仍停在 ASK，
     * 「同类只问一次」只在刷新前靠前端兜、刷新后必退化（三席 P1 实证）。
     */
    public static boolean needsRebuild(String threadId) {
        if (threadId == null) {
            return false;
        }
        String last = LAST_BUILT.get(threadId);
        if (last == null) {
            return false;
        }
        if (!last.equals(level(threadId))) {
            return true;
        }
        int builtGen = BUILT_GEN.getOrDefault(threadId, 0);
        SharedBackend backend = shared();
        if (backend != null && backend.redisReadPreferred()) {
            try {
                return builtGen != (int) Math.min(Integer.MAX_VALUE, backend.approvedGen(threadId));
            } catch (Exception e) {
                // 读不到共享代次：宁可重建一次（重算规则），不能沿用旧规则 fail-open
                return true;
            }
        }
        return builtGen != APPROVED_GEN.getOrDefault(threadId, 0);
    }

    /**
     * 权限面变化触发的重建：resolver 置位（档位翻转 / 批准代次 / 租户装配指纹变化），
     * 提示 AgentFactory 建完 agent 后必须把新规则装回旧会话的持久化 state。
     */
    public static void markRuleInstallPending(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        evictIfCrowded();
        RULE_INSTALL_PENDING.put(threadId, Boolean.TRUE);
        SharedBackend backend = shared();
        if (backend != null) {
            try {
                backend.markRuleInstallPending(threadId);
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(AssistantAutonomyHolder.class)
                    .warn("[autonomy] 共享规则装回标记写入失败 thread={}: {}", threadId, e.getMessage());
            }
        }
    }

    /** AgentFactory 消费：取走并清除（一次重建只装一次；无标记=false 不装） */
    public static boolean consumeRuleInstallPending(String threadId) {
        if (threadId == null) {
            return false;
        }
        boolean local = Boolean.TRUE.equals(RULE_INSTALL_PENDING.remove(threadId));
        SharedBackend backend = shared();
        if (backend != null && backend.redisReadPreferred()) {
            try {
                return backend.consumeRuleInstallPending(threadId) || local;
            } catch (Exception e) {
                // 读不到共享标记：强制装一次当前规则，避免旧规则 fail-open
                return true;
            }
        }
        return local;
    }
}
