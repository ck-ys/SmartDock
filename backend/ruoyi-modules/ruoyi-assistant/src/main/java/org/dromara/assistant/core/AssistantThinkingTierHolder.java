package org.dromara.assistant.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 思考档位会话级暂存（AU-03 / 拍板 Q3=C，2026-10-07）。
 *
 * <p>前端 Composer 三档（fast/balanced/deep）经 AGUI forwardedProps.thinkTier 逐请求上行；
 * resolver 写入本表，模型装饰器 stream 时按 threadId 读取——agent 按 (user,thread) 缓存，
 * 同会话切档无需重建 agent，下一请求即生效。</p>
 */
public final class AssistantThinkingTierHolder {

    public static final String TIER_FAST = "fast";
    public static final String TIER_BALANCED = "balanced";
    public static final String TIER_DEEP = "deep";

    /** 会话粒度；上限保护：超限整体清空（丢档只影响相关会话下一请求回落默认档，可接受） */
    private static final Map<String, String> BY_THREAD = new ConcurrentHashMap<>();
    private static final int CAP = 50_000;

    private AssistantThinkingTierHolder() {
    }

    public static void set(String threadId, Object rawTier) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        if (BY_THREAD.size() > CAP) {
            BY_THREAD.clear();
        }
        BY_THREAD.put(threadId, normalize(rawTier));
    }

    public static String get(String threadId) {
        return threadId == null ? TIER_BALANCED : BY_THREAD.getOrDefault(threadId, TIER_BALANCED);
    }

    /** 坏值/缺省一律 balanced（=不追加 reasoning 参数，走服务端默认档） */
    public static String normalize(Object raw) {
        String s = raw == null ? null : String.valueOf(raw).trim().toLowerCase();
        return TIER_FAST.equals(s) || TIER_DEEP.equals(s) ? s : TIER_BALANCED;
    }
}
