package org.dromara.assistant.core;

/**
 * 流线程 run 上下文桥（批G G3，2026-10-06）。
 *
 * <p>背景：动作账本幂等键需要**当前 runId**，但 agent/工厂是会话级缓存（一个
 * thread 一个 agent 实例复用多轮 run），构造时固化的 runId 会跨轮陈旧。enricher
 * 在流线程逐事件批次刷新本 ThreadLocal——工具若在同流线程执行（ReAct 同步循环）
 * 即可读到当前 runId；读不到（线程换乘）返回 null，调用方降级 UUID 键（登记）。</p>
 */
public final class AssistantRunContext {

    private static final ThreadLocal<String> CURRENT_RUN = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_THREAD = new ThreadLocal<>();

    private AssistantRunContext() {
    }

    public static void refresh(String runId, String threadId) {
        CURRENT_RUN.set(runId);
        CURRENT_THREAD.set(threadId);
    }

    public static void clear() {
        CURRENT_RUN.remove();
        CURRENT_THREAD.remove();
    }

    public static String currentRunId() {
        return CURRENT_RUN.get();
    }

    public static String currentThreadId() {
        return CURRENT_THREAD.get();
    }
}
