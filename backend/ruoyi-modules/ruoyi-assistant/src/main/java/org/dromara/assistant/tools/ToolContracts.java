package org.dromara.assistant.tools;

/**
 * 工具契约常量 v1（批B B0，2026-10-04；契约表= document/evidence/assistant-p1/tool-contracts.md）。
 *
 * <p>五件查询工具共用的返回形状/超时/截断口径——常量集中此处，工具实现与测试断言
 * 同源，防口径漂移。</p>
 */
public final class ToolContracts {

    private ToolContracts() {
    }

    /** 每页行数上限（返回形状"前 20 行+总数+续读参数"） */
    public static final int MAX_ROWS = 20;

    /** 常规工具超时（毫秒）；超时即返回 TIMEOUT 错误 JSON，不抛异常炸会话 */
    public static final long DEFAULT_TIMEOUT_MS = 10_000;

    /** 慢工具白名单超时（deadline_alerts 跨任务扫描等） */
    public static final long SLOW_TOOL_TIMEOUT_MS = 30_000;

    // ===== 返回 JSON 键（契约钉死，前端/测试按此断言） =====
    /** 数据截止时间戳（ISO-8601 带时区）——答案的 as-of 口径 */
    public static final String KEY_AS_OF = "asOf";
    /** 回显本次查询身份（tenantId/userId）——防模型张冠李戴、跨租户对账断言锚点 */
    public static final String KEY_QUERIED_AS = "queriedAs";
    /** 命中总数（未截断的完整计数） */
    public static final String KEY_TOTAL = "total";
    /** 本页数据行（≤ MAX_ROWS） */
    public static final String KEY_ROWS = "rows";
    /** 是否被截断（total > rows.length） */
    public static final String KEY_TRUNCATED = "truncated";
    /** 续读页码（truncated=true 时才有） */
    public static final String KEY_NEXT_PAGE = "nextPage";
    /** 错误码（TIMEOUT/DENIED/ERROR），出现即本次无 rows */
    public static final String KEY_ERROR = "error";
}
