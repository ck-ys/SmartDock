package org.dromara.assistant.core;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 联网搜索会话级开关暂存（业务批3-④ #17，2026-10-12）。
 *
 * <p>前端 Composer 联网开关经 AGUI forwardedProps.webSearchEnabled 逐请求上行；
 * resolver 写入本表，工具注册闸与缓存失效按 threadId 读取——与
 * {@link AssistantModelChoiceHolder} 同款「会话级暂存、下一请求生效」模式。</p>
 *
 * <p>口径：null/非真值=关（默认关，grill Q6 定稿）。agent 按 (user,thread) 缓存且
 * 工具集建时固化——开关翻转时 resolver 对比 {@link #needsRebuild} 失效缓存 agent
 * 重建，否则关掉后模型仍带着 web_search schema（幻觉调用转告，体验劣化）。</p>
 */
public final class AssistantWebSearchHolder {

    /** 会话粒度；上限保护：超限两张表整体清空（丢状态只影响相关会话下一请求回落关，可接受）。
     *  两表必须同清（三审 glm53 P2-3）：只清 LAST_BUILT 留 BY_THREAD 时 needsRebuild 恒 false，
     *  在位缓存 agent 的工具集与当前开关永久脱钩、翻转不再触发重建。 */
    private static final Map<String, Boolean> BY_THREAD = new ConcurrentHashMap<>();
    private static final int CAP = 50_000;

    /* ---- 缓存失效基准（镜像 AssistantModelChoiceHolder）：记「最近一次建 agent 时的
     * 开关原值」，resolver 对比差异→removeSession 重建（工具集随开关增删）。 */
    private static final Map<String, Boolean> LAST_BUILT = new ConcurrentHashMap<>();

    private AssistantWebSearchHolder() {
    }

    /** 双表同步驱逐：任一超限两表齐清，保 needsRebuild 对称可判 */
    private static void evictIfCrowded() {
        if (BY_THREAD.size() > CAP || LAST_BUILT.size() > CAP) {
            BY_THREAD.clear();
            LAST_BUILT.clear();
        }
    }

    /** raw 非真值（true/1/yes，忽略大小写）=关 */
    public static void set(String threadId, Object raw) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        evictIfCrowded();
        if (parse(raw)) {
            BY_THREAD.put(threadId, Boolean.TRUE);
        } else {
            BY_THREAD.remove(threadId);
        }
    }

    /** 该会话联网是否开启；false=关（默认） */
    public static boolean isOn(String threadId) {
        return threadId != null && Boolean.TRUE.equals(BY_THREAD.get(threadId));
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

    private static boolean parse(Object raw) {
        if (raw == null) {
            return false;
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }
}
