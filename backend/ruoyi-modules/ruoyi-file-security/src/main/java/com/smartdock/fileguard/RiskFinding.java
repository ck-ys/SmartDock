package com.smartdock.fileguard;

/**
 * 内容深扫命中项（L3 层的输出，不可变值对象）。
 *
 * @param rule    命中规则机读码（日志/单测断言用）：OFFICE_MACRO / PDF_JAVASCRIPT / SVG_XSS / PARSE_ANOMALY
 * @param message 用户可读的命中说明（进审标签展示与日志详情共用）
 * @author smartdock
 */
public record RiskFinding(String rule, String message) {
}
