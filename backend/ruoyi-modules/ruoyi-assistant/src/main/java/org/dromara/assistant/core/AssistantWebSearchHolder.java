package org.dromara.assistant.core;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 联网搜索会话级开关暂存（题10 案1，2026-10-31 拍板：默认可用，可停用）。
 *
 * <p>口径反转：<b>默认开</b>——前端不再有「开/关」手动拨动，联网交给模型按问题判断；
 * 用户只在「＋」面板里选择「本会话停用」。前端经 AGUI forwardedProps.webSearchEnabled
 * 逐请求上行，resolver 写入本表：只有显式假值（false/0/no/off）才落「停用」集合，
 * 缺省/真值一律按可用。</p>
 *
 * <p>工具集建时固化：开关翻转时 resolver 对比 {@link #needsRebuild} 失效缓存 agent
 * 重建，否则停用后模型仍带着 web_search schema（幻觉调用转告，体验劣化）。</p>
 */
public final class AssistantWebSearchHolder {

    /** 显式停用的线程集合（默认开，只有用户主动停用才入库）。
     *  与 LAST_BUILT 双表同清：只清一张会让 needsRebuild 恒 false，在位缓存 agent 的
     *  工具集与当前开关永久脱钩、翻转不再触发重建。 */
    private static final Set<String> OFF = ConcurrentHashMap.newKeySet();
    private static final int CAP = 50_000;

    /* ---- 缓存失效基准（镜像 AssistantModelChoiceHolder）：记「最近一次建 agent 时的
     * 开关原值」，resolver 对比差异→removeSession 重建（工具集随开关增删）。 */
    private static final Map<String, Boolean> LAST_BUILT = new ConcurrentHashMap<>();

    private AssistantWebSearchHolder() {
    }

    /** 双表同步驱逐：任一超限两表齐清，保 needsRebuild 对称可判 */
    private static void evictIfCrowded() {
        if (OFF.size() > CAP || LAST_BUILT.size() > CAP) {
            OFF.clear();
            LAST_BUILT.clear();
        }
    }

    /** 显式假值（false/0/no/off，忽略大小写）=停用；显式真值=恢复可用；
     *  null/缺省=不改现状（旧前端不带键、脚本化调用不会把用户显式停用悄悄改回联网） */
    public static void set(String threadId, Object raw) {
        if (threadId == null || threadId.isBlank() || raw == null) {
            return;
        }
        evictIfCrowded();
        if (isExplicitOff(raw)) {
            OFF.add(threadId);
        } else {
            OFF.remove(threadId);
        }
    }

    /** 该会话联网是否可用；true=默认（模型自主判断） */
    public static boolean isOn(String threadId) {
        return threadId != null && !OFF.contains(threadId);
    }

    /** AgentFactory 建 agent 后回写本次基准 */
    public static void markBuilt(String threadId, boolean onAtBuild) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        evictIfCrowded();
        LAST_BUILT.put(threadId, onAtBuild);
    }

    /**
     * 当前声明与上次建 agent 基准不同 → 该 thread 缓存 agent 需失效重建（工具集固化）。
     * 从未建过（基准 null）=无缓存可失效，首次构建自然按当前声明走，不触发。
     */
    public static boolean needsRebuild(String threadId) {
        if (threadId == null) {
            return false;
        }
        Boolean last = LAST_BUILT.get(threadId);
        if (last == null) {
            return false;
        }
        return last != isOn(threadId);
    }

    private static boolean isExplicitOff(Object raw) {
        if (raw == null) {
            return false;
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return "false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s);
    }
}
