package org.dromara.assistant.core;

/**
 * 上下文压缩策略（批A-1，2026-10-22 拍板 Q1/Q5/Q7）：ZCode 绝对预留链的本地实现。
 *
 * <p>ZCode 源码实证（apps/zcode-cli/packages/core/src/compact/policy.ts）：
 * 触发线 = 窗口 − min(最大输出额度, 21000) − 13000，给回答和缓冲留固定配额，
 * 不做固定百分比——窗口越大越晚压，小窗口不漏压。</p>
 *
 * <p>本仓约束（P14 探针实证）：deepseek 族模型 {@code getContextWindowSize()}=0，
 * 不能依赖模型自报；窗口与输出额度一律取本仓 {@code dcs_ai_config} 的配置值
 * （模型构建时同源透传），未配置才用 20 万/3.2 万兜底。</p>
 */
public final class AssistantCompactionPolicy {

    private AssistantCompactionPolicy() {
    }

    /** 窗口兜底：配置缺失时按 20 万（与 ZCode DEFAULT_COMPACT_CONTEXT_WINDOW 同值） */
    public static final int FALLBACK_CONTEXT_WINDOW = 200_000;
    /** 输出额度兜底：3.2 万（ZCode DEFAULT_AUTOCOMPACT_OUTPUT_RESERVE_TOKENS） */
    public static final int DEFAULT_OUTPUT_RESERVE = 32_000;
    /** 输出预留上限：2.1 万（ZCode PREFLIGHT_AUTOCOMPACT_OUTPUT_RESERVE_TOKENS） */
    public static final int MAX_OUTPUT_RESERVE = 21_000;
    /** 反应缓冲：1.3 万（ZCode AUTOCOMPACT_BUFFER_TOKENS） */
    public static final int BUFFER_TOKENS = 13_000;
    /** 触发线地板：低于 4 千的触发线没有压缩收益，且 0 会被框架当成"按窗口动态算" */
    public static final int MIN_TRIGGER_TOKENS = 4_000;
    /** 尾窗按轮保留：最后 1 轮原文 = user + assistant 两条消息（Q5-A 推荐案） */
    public static final int KEEP_MESSAGES = 2;
    /** 微压缩保留最近 N 组工具结果（Q6-A 照抄 ZCode） */
    public static final int MICROCOMPACT_KEEP_GROUPS = 5;
    /** 微压缩最小节省门槛，省不够不做（ZCode DEFAULT_MICROCOMPACT_MIN_TOKEN_SAVINGS） */
    public static final int MICROCOMPACT_MIN_SAVINGS = 256;
    /** 微压缩占位符（与 ZCode 清理文案同义，便于跨系统对照排查） */
    public static final String MICROCOMPACT_CLEARED_TEXT = "[Old tool result content cleared]";
    /** 微压缩不清理的结果：读取类/记忆类结果语义密度高，清掉等于删证据。
     *  二审 P2：只列本仓 ToolRegistry 真实存在的工具名（read_attachment/save_memory/view_skill）——
     *  ZCode 名单里的 read_file/memory_search/memory_get/session_search 本仓没有，虚名空转且误导 */
    public static final java.util.Set<String> MICROCOMPACT_EXCLUDED_TOOLS =
        java.util.Set.of("read_attachment", "save_memory", "view_skill");

    /** 生效窗口：配置值 > 0 才认，否则 20 万兜底 */
    public static int effectiveContextWindow(Integer configuredWindow) {
        return configuredWindow != null && configuredWindow > 0
            ? configuredWindow : FALLBACK_CONTEXT_WINDOW;
    }

    /** 输出预留：min(配置最大输出 ?? 3.2 万, 2.1 万) */
    public static int outputReserve(Integer maxOutputTokens) {
        int maxOut = maxOutputTokens != null && maxOutputTokens > 0
            ? maxOutputTokens : DEFAULT_OUTPUT_RESERVE;
        return Math.min(maxOut, MAX_OUTPUT_RESERVE);
    }

    /**
     * 触发线 = max(4000, 窗口 − 输出预留 − 1.3 万)。
     * 例：128K 窗口/最大输出≥21K → 128000−21000−13000=94000；200K → 166000。
     */
    public static int deriveTriggerTokens(Integer configuredWindow, Integer maxOutputTokens) {
        int window = effectiveContextWindow(configuredWindow);
        return Math.max(MIN_TRIGGER_TOKENS, window - outputReserve(maxOutputTokens) - BUFFER_TOKENS);
    }

    /**
     * 微压缩触发线：自动触发线的 9 成与「触发线 − 2000」取小（ZCode 同款双保险），
     * 即先清旧工具结果、常常清完就不必再走全量摘要。
     */
    public static int microcompactThreshold(int triggerTokens) {
        int ratio = (int) Math.floor(triggerTokens * 0.9);
        return Math.max(0, Math.min(ratio, triggerTokens - 2_000));
    }
}
