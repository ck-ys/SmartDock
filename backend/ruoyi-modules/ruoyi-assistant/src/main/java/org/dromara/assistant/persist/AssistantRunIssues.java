package org.dromara.assistant.persist;

/**
 * 「这一轮为什么没回答」原因码纯函数（2026-11-11 拍板 Q1-3/Q2-2/Q4-2）。
 *
 * <p>两张嘴说话：判据只认服务商错误帧里的 code/message 与「本轮有没有正文/工具」，
 * 输出固定六码——empty（正常结束但一个字没回）/ rate / quota / timeout / auth / other。
 * 人话文案放前端映射表，这里零依赖、可单测钉死。</p>
 *
 * <p>边界（拍板时的共识）：准入闸/计费前置拦截发生在 run 行落库之前，服务端没有
 * 可挂靠的回合，不进问题表——那类拒绝前端本来就有红条话术；这里只兜「run 已经
 * 跑起来之后」的原因。</p>
 */
public final class AssistantRunIssues {

    /** 正常结束但无正文无工具（HTTP 200，正文为空） */
    public static final String EMPTY = "empty";
    /** 被限流（429 / rate limit / 使用人数超限） */
    public static final String RATE = "rate";
    /** 余额或配额用尽（quota / 额度不足 / 欠费门槛） */
    public static final String QUOTA = "quota";
    /** 响应超时 */
    public static final String TIMEOUT = "timeout";
    /** 鉴权失败（钥匙无效/过期，401/403） */
    public static final String AUTH = "auth";
    /** 未识别的其它错误 */
    public static final String OTHER = "other";

    /** detail 列宽对齐（varchar(512)），人读 200 字封顶 */
    static final int DETAIL_MAX = 200;

    private AssistantRunIssues() {
    }

    /**
     * 错误帧 → 原因码。规则按「先具体后兜底」排序：429 先于配额词（"429 quota exceeded"
     * 是限流不是余额尽），鉴权词先于超时词（401 与 timeout 同现时钥匙问题更可行动）。
     * code 与 message 都参与匹配，判据写不清宁可落 other——误判会把用户指去充值/换钥匙，
     * 比不说更糟。
     */
    public static String classify(String code, String message) {
        String text = ((code == null ? "" : code) + " " + (message == null ? "" : message)).toLowerCase();
        if (text.isEmpty()) {
            return OTHER;
        }
        if (text.contains("429") || text.contains("rate limit") || text.contains("ratelimit")
            || text.contains("too many requests") || text.contains("限流") || text.contains("请求过于频繁")
            || text.contains("当前使用人数较多")) {
            return RATE;
        }
        if (text.contains("quota") || text.contains("余额") || text.contains("额度") || text.contains("配额")
            || text.contains("insufficient") || text.contains("402") || text.contains("usage limit")
            || text.contains("token plan") || text.contains("欠费") || text.contains("arrears")) {
            return QUOTA;
        }
        if (text.contains("401") || text.contains("403") || text.contains("unauthorized")
            || text.contains("forbidden") || text.contains("api key") || text.contains("apikey")
            || text.contains("invalid key") || text.contains("鉴权") || text.contains("authentication")) {
            return AUTH;
        }
        if (text.contains("timeout") || text.contains("timed out") || text.contains("sockettimeout")
            || text.contains("read timeout") || text.contains("超时")) {
            return TIMEOUT;
        }
        return OTHER;
    }

    /**
     * 服务商原文脱敏（Q2-2：码 + 脱敏原文，原文只给管理员看）。按序处理：
     * URL → [链接]；sk-/Bearer 令牌 → [密钥]；≥24 位连续不空白串（hex/base64/请求 id
     * 常态）→ [长串]；空白折叠；200 字截断补「…」。null/空白返回 null（empty 类无原文）。
     */
    public static String sanitizeDetail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw
            .replaceAll("(?i)https?://\\S+", "[链接]")
            .replaceAll("(?i)(sk|pk|rk)-[a-z0-9_\\-]{8,}", "[密钥]")
            .replaceAll("(?i)bearer\\s+[a-z0-9._\\-]{8,}", "Bearer [令牌]")
            .replaceAll("(?i)[a-z0-9+/=_\\-]{24,}", "[长串]")
            .replaceAll("\\s+", " ")
            .trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() <= DETAIL_MAX ? s : s.substring(0, DETAIL_MAX) + "…";
    }
}
