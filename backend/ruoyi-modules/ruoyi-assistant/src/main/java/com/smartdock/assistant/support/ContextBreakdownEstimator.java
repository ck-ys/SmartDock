package com.smartdock.assistant.support;

/**
 * 批 A Q6（2026-11-14）：上下文构成口径；2026-11-15 按 Q6 框架板 Q3 推荐改为<b>只数字符</b>。
 *
 * <p>为什么不再折 token：中文、英文、代码、JSON 的折算系数本来就不一样，折出来的 token
 * 只在自己内部纵向可比；字符数是客观量，换厂商也不变，界面按需折算并在脚注常驻
 * 「占比为估算」。与 ZCode 协议一致（它传的就是 chars）。</p>
 *
 * <p>老口径的 2.5 字符/token 估算已删除（面板不再显示分项 token 数）；真值走
 * totalTokens（该会话最新一次模型调用），本类只算「构成占比」这一笔估算。</p>
 */
public final class ContextBreakdownEstimator {

    /** 口径标记（随 VO 下发，供面板脚注 tooltip 展示；改口径必须改这里，单测钉死）。 */
    public static final String METHOD = "chars-v1";

    private ContextBreakdownEstimator() {
    }

    /** 文本段字符数（null/空 = 0）。不做任何折算——字符数就是字符数。
     *  口径固定为 UTF-16 码元（String.length）：与 ZCode 的 JS `string.length` 同口径，
     *  纯中英文场景与「字数」一致；emoji/增补平面字符按 2 计（2026-11-15 三审席3 用单测钉死，
     *  改口径必须同时改测试与面板脚注）。 */
    public static long charsOf(String text) {
        return text == null ? 0 : text.length();
    }

    /** 一组文本的字符数合计（null 元素按 0）。 */
    public static long charsOf(java.util.Collection<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return 0;
        }
        return parts.stream().mapToLong(ContextBreakdownEstimator::charsOf).sum();
    }

    /** 占比：四舍五入到 0.1 个百分点（面板行内只显示百分比，不给分项字符数）。sum<=0 防 0 除。 */
    public static double pctOf(long part, long sum) {
        if (sum <= 0 || part <= 0) {
            return 0;
        }
        return Math.round(part * 1000.0 / sum) / 10.0;
    }
}
