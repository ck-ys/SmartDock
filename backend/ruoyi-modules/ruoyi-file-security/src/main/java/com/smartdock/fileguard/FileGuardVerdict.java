package com.smartdock.fileguard;

/**
 * 一次文件安全检测的完整判定（不可变值对象，批二接线层据此拒传/建审/放行）。
 *
 * @param action      处置动作
 * @param rule        命中规则机读码：EXTENSION_WHITELIST / MAGIC_MISMATCH / 深扫命中的
 *                    RiskFinding.rule / DEEP_SCAN_SKIPPED / CLEAN
 * @param userMessage 用户可见文案（拒传错误信息 / 进审风险标签说明；放行时为空串）
 * @author smartdock
 */
public record FileGuardVerdict(GuardAction action, String rule, String userMessage) {

    /** 干净放行 */
    public static FileGuardVerdict pass() {
        return new FileGuardVerdict(GuardAction.PASS, "CLEAN", "");
    }

    /** 超过深扫上限的放行（规则码区分，便于观察日志里的跳过量） */
    public static FileGuardVerdict passSkippedDeepScan() {
        return new FileGuardVerdict(GuardAction.PASS, "DEEP_SCAN_SKIPPED", "");
    }

    /** 拒传（确定性危险） */
    public static FileGuardVerdict deny(String rule, String userMessage) {
        return new FileGuardVerdict(GuardAction.DENY, rule, userMessage);
    }

    /** 强制进审（灰度信号） */
    public static FileGuardVerdict review(String rule, String userMessage) {
        return new FileGuardVerdict(GuardAction.REVIEW, rule, userMessage);
    }
}
