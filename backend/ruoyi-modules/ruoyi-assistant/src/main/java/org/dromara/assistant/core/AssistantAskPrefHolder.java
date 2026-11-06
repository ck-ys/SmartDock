package org.dromara.assistant.core;

import java.util.Map;

/**
 * 反问卡「自动继续」会话级开关暂存（C 七行·设置页「常规」，2026-11-06）。
 *
 * <p>口径对齐 ZCode（{@code askUserQuestionAutoResolutionEnabled}，默认开）：反问卡 5 分钟
 * 没答，按「空答案」收尾，模型拿到「用户没选，按最稳妥的默认继续」——不是中断，也不是
 * 替用户随便点一个。关掉则不自动继续，等到超时才收尾。</p>
 *
 * <p>前端经 AGUI forwardedProps.askAutoContinue 逐请求上行；缺省（旧前端/自动化跑批）
 * 按默认开处理——ZCode 同款默认值，且自动化无人值守时更该继续而不是挂死。</p>
 */
public final class AssistantAskPrefHolder {

    /** 显式关闭的线程集合（默认开，只有用户主动关才进集合）。
     *  三审 P2：原实现超 CAP 就 clear()——一次清空把所有人「关闭自动继续」的显式设置
     *  静默恢复成默认开。改 LRU 淘汰：只挤掉最久没写过的行，不动近期活跃会话。 */
    private static final int CAP = 50_000;
    private static final Map<String, Boolean> AUTO = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > CAP;
            }
        });

    private AssistantAskPrefHolder() {
    }

    /** 显式假值（false/0/no/off，忽略大小写）=关；显式真值=开；null/缺省=不改现状。 */
    public static void set(String threadId, Object raw) {
        if (threadId == null || threadId.isBlank() || raw == null) {
            return;
        }
        AUTO.put(threadId, !isExplicitOff(raw));
    }

    /** 该会话是否自动继续；未知线程=默认开（ZCode 默认口径）。 */
    public static boolean isAutoContinue(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return true;
        }
        Boolean v = AUTO.get(threadId);
        return v == null || v;
    }

    private static boolean isExplicitOff(Object raw) {
        String s = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        return "false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s);
    }
}
