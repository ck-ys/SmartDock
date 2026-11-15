package com.smartdock.assistant.domain.vo;

import java.util.List;

/**
 * 批 A Q6（2026-11-14）：本会话上下文构成；2026-11-15 按 Q6 框架板推荐改口径：
 * 只报<b>字符数</b>（客观量，换厂商不变），占比 = 该桶字符数 ÷ 全部桶字符数之和；
 * 界面折算并常驻「占比为估算」。分段照 ZCode 分法（system_prompt / memory / messages /
 * tools_prompt / system_tools / skills / other），0 桶不下发；MCP 工具段（mcp_tools）本版
 * 不下发——工具 schema 需活连接 listTools，状态接口不做网络调用，留待下批。
 *
 * <ul>
 *   <li>totalTokens / windowTokens 是<b>真值</b>（该会话最新一次模型调用的 tokens、生效模型窗口）；</li>
 *   <li>buckets 里 chars 是<b>客观字符数</b>、pct 是折算后的估算占比（降序，四舍五入到 0.1）；</li>
 *   <li>subs 只给系统提示词桶（逐段展开），前端行内只显示 pct、不显示分项字符数。</li>
 * </ul>
 */
public record ContextBreakdownVo(
    Long totalTokens,
    Long windowTokens,
    String method,
    List<Bucket> buckets
) {

    /** key：system_prompt / memory / tools_prompt / system_tools / skills / other / messages */
    public record Bucket(String key, String label, long chars, double pct, boolean lowConfidence, List<Sub> subs) {
    }

    /** 系统提示词桶的逐段细分（title + 段内占比） */
    public record Sub(String name, double pct) {
    }
}
