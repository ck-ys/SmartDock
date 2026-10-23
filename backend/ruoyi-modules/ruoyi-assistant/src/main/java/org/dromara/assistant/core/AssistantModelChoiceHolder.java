package org.dromara.assistant.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户模型自选会话级暂存（批7 Q5 消费端/Q12-④，2026-10-09）。
 *
 * <p>前端 Composer 模型选择器经 AGUI forwardedProps.modelConfigId 逐请求上行；
 * resolver 写入本表，模型工厂装配时按 threadId 读取——与 {@link AssistantThinkingTierHolder}
 * 同款「会话级暂存、下一请求生效」模式。优先级链第一段（用户会话选择 &gt; 助手默认 &gt;
 * 接口默认 &gt; 兜底），由 AssistantModelFactory.resolveConfigWithUserChoice 消费。</p>
 *
 * <p>安全口径：本表只存「用户声明的 configId」，生效与否由工厂侧双重校验
 * （allowUserSwitch 开关 + resolveConfigById 租户/可用/CHAT/CNY 闸）裁决——
 * 绕过前端直接伪造 forwardedProps 也点不动被停用/别租户的行。</p>
 */
public final class AssistantModelChoiceHolder {

    /** 会话粒度；上限保护：超限整体清空（丢选择只影响相关会话下一请求回落默认链，可接受） */
    private static final Map<String, Long> BY_THREAD = new ConcurrentHashMap<>();
    private static final int CAP = 50_000;

    private AssistantModelChoiceHolder() {
    }

    /** raw 非数字/空=清该会话选择（前端「跟随默认」上行 null 时归位） */
    public static void set(String threadId, Object raw) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        if (BY_THREAD.size() > CAP) {
            BY_THREAD.clear();
        }
        Long parsed = parse(raw);
        if (parsed == null) {
            BY_THREAD.remove(threadId);
        } else {
            BY_THREAD.put(threadId, parsed);
        }
    }

    /** 该会话用户声明的 configId；null=未选择（走默认链） */
    public static Long get(String threadId) {
        return threadId == null ? null : BY_THREAD.get(threadId);
    }

    /* ---- 批7 缓存失效基准：agent 按 (user,thread) 缓存且模型建时固化——
     * 用户中途切模型若不失效缓存 agent，同会话永远跑旧模型（2026-10-09 E2E 实证）。
     * LAST_BUILT 记「最近一次建 agent 时的用户选择原值」（含回退也记 picked，
     * 防 picked 持续失效时每请求重建）；resolver 对比差异→removeSession 重建。 */

    private static final Map<String, Long> LAST_BUILT = new ConcurrentHashMap<>();
    /**
     * 业务批4 Q1-A（2026-10-13 拍板）：最近一次建 agent 时的「自选范围白名单指纹」。
     * 管理员收紧/放宽 switch_scope 后，该租户所有缓存会话下一次请求指纹失配 → 失效重建，
     * 不再吃满 60 分钟会话超时才生效（零 DDL：指纹=当前白名单集合快照，复用本表机制）。
     */
    private static final Map<String, String> LAST_BUILT_SCOPE = new ConcurrentHashMap<>();
    /** 哨兵：CHM 不容 null 值——0=本次按默认链建（用户无选择）。configId 恒为正雪花，0 不与真值同构
     * （批8-3 回修 dspro P0-1：无 pin 会话首建 agent 时 markBuilt 收到 null，裸 put 对 CHM 必抛 NPE） */
    private static final Long NO_PICK = 0L;

    /** 白名单集合 → 指纹串（空集=空串；排序后拼接保证集合相等则指纹相等） */
    public static String scopeFingerprint(java.util.Set<Long> scopeIds) {
        if (scopeIds == null || scopeIds.isEmpty()) {
            return "";
        }
        return scopeIds.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * 业务批5 Q4-A（2026-10-13 拍板）：默认感知指纹 = 自选白名单 + 助手默认指向。
     * 管理员换助手默认模型后，该租户所有缓存会话下一次请求指纹失配 → 失效重建，
     * 不再吃满 60 分钟会话超时才生效（scopeFingerprint 同机制扩一段；建/比两侧必须同用本方法）。
     * G2 三审回修（dspro P1-2）：usableSignature（可用 CHAT 行 configId:星标 签名）拼第三段
     * ——scope/默认 ID 都不变但星标切换/默认行停用时，实际生效模型已变，旧指纹抓不住
     * （缓存 agent 继续跑旧模型最长 60 分钟、徽标却显示新名字的"看 A 跑 B"）。
     */
    public static String defaultAwareFingerprint(java.util.Set<Long> scopeIds, Long assistantDefaultConfigId,
                                                 String usableSignature) {
        return scopeFingerprint(scopeIds) + "|def:" + (assistantDefaultConfigId == null ? "" : assistantDefaultConfigId)
            + "|sig:" + (usableSignature == null ? "" : usableSignature);
    }

    /** AgentFactory 建 agent 后回写本次基准（picked 原值；null=本次按默认链建，记哨兵 0） */
    public static void markBuilt(String threadId, Long pickedAtBuild) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        if (LAST_BUILT.size() > CAP) {
            // 双表必须同清（WebSearchHolder 同款纪律）：单清一张会让另一张的比对基准悬空
            LAST_BUILT.clear();
            LAST_BUILT_SCOPE.clear();
        }
        LAST_BUILT.put(threadId, pickedAtBuild == null ? NO_PICK : pickedAtBuild);
    }

    /** 业务批4 Q1-A：建 agent 后回写当时的白名单指纹（与 markBuilt 成对调用） */
    public static void markScopeBuilt(String threadId, String scopeFingerprint) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        if (LAST_BUILT_SCOPE.size() > CAP) {
            LAST_BUILT.clear();
            LAST_BUILT_SCOPE.clear();
        }
        LAST_BUILT_SCOPE.put(threadId, scopeFingerprint == null ? "" : scopeFingerprint);
    }

    /** 业务批4 Q1-A：从未建过（无基准）=无缓存可失效不触发；指纹失配=该 thread 缓存 agent 需重建 */
    public static boolean needsScopeRebuild(String threadId, String currentScopeFingerprint) {
        if (threadId == null) {
            return false;
        }
        String last = LAST_BUILT_SCOPE.get(threadId);
        if (last == null) {
            return false;
        }
        return !last.equals(currentScopeFingerprint == null ? "" : currentScopeFingerprint);
    }

    /**
     * 当前声明（含 null=跟随默认）与上次建 agent 基准不同 → 该 thread 缓存 agent 需失效重建。
     * 批8-3 回修（glm53 P1-1/dspro P1-2/dsflash P1-1 三席交叉实锤）：切回「跟随默认」也是一次
     * 真实声明变化——旧实现 {@code picked != null} 短路导致恢复默认后旧模型继续跑（计费失约）。
     * 从未建过（基准 null）=无缓存可失效，首次构建自然按当前声明走，不触发。
     * 并发假设：同 threadId 的 AGUI 请求串行（前端单飞）；并发交错至多多一次重建，自愈无错模型。
     */
    public static boolean needsRebuild(String threadId) {
        if (threadId == null) {
            return false;
        }
        Long last = LAST_BUILT.get(threadId);
        if (last == null) {
            return false;
        }
        Long picked = BY_THREAD.get(threadId);
        return !(picked == null ? NO_PICK : picked).equals(last);
    }

    // ===== G1 遗留#3：租户装配面指纹（toolGate/memoryEnabled/compaction） =====
    // 此前三者均不参与任何指纹——管理员改工具开关后旧 agent 会话缓存不失效，最长
    // 60 分钟才随会话超时重建。指纹入链后：改完下一请求即 removeSession 重建。
    private static final java.util.Map<String, String> CONFIG_FINGERPRINTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 装配面指纹：只取影响 agent 装配的租户配置段（TreeMap 序稳定防误判）；
     *  批A-1：压缩触发线改为按模型窗口派生，纳入指纹——改窗口/输出额度后下一请求即重建；
     *  N3：本租户搜索 Key 可用性纳入——管理员刚配好 Key 时缓存会话下一请求即重建，立即生效 */
    public static String assemblyFingerprint(org.dromara.assistant.service.AssistantConfigService.EffectiveConfig cfg,
                                             int compactionTriggerTokens, boolean webSearchKeyUsable) {
        return (cfg.toolGate() == null ? "" : new java.util.TreeMap<>(cfg.toolGate()).toString())
            + "|" + cfg.memoryEnabled() + "|" + compactionTriggerTokens + "|" + webSearchKeyUsable;
    }

    public static void markConfigBuilt(String threadId, String fingerprint) {
        // 三审回修（P2）：与同文件既有三表同款 CAP 整体清空纪律（防无界增长）
        if (CONFIG_FINGERPRINTS.size() > CAP) {
            CONFIG_FINGERPRINTS.clear();
        }
        if (threadId != null) {
            CONFIG_FINGERPRINTS.put(threadId, fingerprint);
        }
    }

    /** null=未建（首建不算失效）；非等=租户装配面变了，需失效重建 */
    public static boolean needsConfigRebuild(String threadId, String fingerprint) {
        if (threadId == null) {
            return false;
        }
        String built = CONFIG_FINGERPRINTS.get(threadId);
        return built != null && !built.equals(fingerprint);
    }

    private static Long parse(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            long v = Long.parseLong(s);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
