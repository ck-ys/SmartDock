package org.dromara.assistant.core;

/**
 * 写操作放行方式（三批③，2026-11-01）：动作账本 release_mode 列的取值域与判定。
 *
 * <p>判定依据=<b>建 agent 时的权限规则快照</b>（{@link ProviderToolRegistry.AuditScope}
 * 的 autonomyAllowed 子集，建时用与 AgentFactory 装 registerProviders 完全同款的
 * autoAllows 谓词），不是执行时的现值——always 档经确认卡放行的工具不在快照集里，
 * 必记 confirm，不误记 auto/once。save_memory 是平台注册即 ALLOW 的工具
 * （ToolRegistry SPECS 行为 ALLOW），与档位无关，恒记 allow。</p>
 *
 * <p>四值口径：confirm=确认卡放行 / once=同类已确认（本会话确认过一次，下一轮直接放行）/
 * auto=全自动（档位全放）/ allow=平台放行（工具本身注册即 ALLOW，无需用户确认）。
 * REJECTED 行（用户点了拒绝）没有放行动作，release_mode 为空。</p>
 */
public final class AssistantReleaseMode {

    /** 确认卡放行（ASK 规则经用户点「确认」后执行） */
    public static final String CONFIRM = "confirm";
    /** 同类已确认（once 档：本会话确认过同名工具，建规则时已直接放行） */
    public static final String ONCE = "once";
    /** 全自动（auto 档：写类工具建规则时一律直接放行） */
    public static final String AUTO = "auto";
    /** 平台放行（工具注册即 ALLOW，如 save_memory，与档位无关） */
    public static final String ALLOW = "allow";

    private AssistantReleaseMode() {
    }

    /**
     * 放行方式判定（纯函数，无静态态读取）。
     *
     * @param toolName               工具名（save_memory 走平台放行首判据）
     * @param autonomyAllowedAtBuild 建时快照：该工具在建 agent 时是否按档位直接放行
     *                               （只来自 AuditScope.autonomyAllowed，不得用 writeTools 全集替代）
     * @param levelAtBuild           建 agent 时的自主权档位快照（auto/once 分流的唯一依据）
     */
    public static String resolve(String toolName, boolean autonomyAllowedAtBuild, String levelAtBuild) {
        if ("save_memory".equals(toolName)) {
            return ALLOW;
        }
        if (!autonomyAllowedAtBuild) {
            return CONFIRM;
        }
        return AssistantAutonomyHolder.ONCE.equals(levelAtBuild) ? ONCE : AUTO;
    }

    /** 中文展示映射（审计页/CSV 共用；空值=拒绝行/历史行，显示「—」） */
    public static String display(String mode) {
        if (mode == null || mode.isBlank()) {
            return "—";
        }
        return switch (mode) {
            case CONFIRM -> "确认后执行";
            case ONCE -> "同类已确认";
            case AUTO -> "全自动";
            case ALLOW -> "平台放行";
            default -> mode;
        };
    }
}
