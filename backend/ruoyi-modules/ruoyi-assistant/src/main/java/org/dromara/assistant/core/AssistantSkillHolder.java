package org.dromara.assistant.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能选择会话级暂存（五需求批 R1，2026-10-13）。
 *
 * <p>前端 Composer 技能胶囊经 AGUI forwardedProps.skillId 逐请求上行；resolver 写入本表
 * （不携带=null 即清除——胶囊摘掉当轮即恢复普通对话）。注入装饰器按 threadId 读取。
 * 写入位置在门禁+归属校验之后（与 thinkTier 同款后移口径，防被拒请求污染）。</p>
 */
public final class AssistantSkillHolder {

    /** 会话粒度；上限保护同 ThinkingTierHolder */
    private static final Map<String, String> BY_THREAD = new ConcurrentHashMap<>();
    private static final int CAP = 50_000;

    private AssistantSkillHolder() {
    }

    /** rawSkillId=null/空白/未知 id 一律清除（防脏值滞留后续轮次） */
    public static void set(String threadId, Object rawSkillId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        if (BY_THREAD.size() > CAP) {
            BY_THREAD.clear();
        }
        String id = rawSkillId == null ? null : String.valueOf(rawSkillId).trim();
        if (id == null || id.isEmpty() || !SkillAssets.exists(id)) {
            BY_THREAD.remove(threadId);
            return;
        }
        BY_THREAD.put(threadId, id);
    }

    /** 当前会话选中的技能 id；未选返回 null */
    public static String get(String threadId) {
        return threadId == null ? null : BY_THREAD.get(threadId);
    }
}
