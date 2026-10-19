package org.dromara.assistant.security;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 助手注入确定性过滤器（批G G4，2026-10-06；判据形态=P15b 探针证毕——
 * 防线必须=确定性过滤器，不依赖模型免疫力）。
 *
 * <p>机制（spec §6 prompt injection 双写）：工具结果 JSON 序列化时若命中指令模式
 * → 结果体追加 {@code injectionFlagged=true} 字段打标降权（前端/模型侧按数据处理，
 * 系统提示已声明含该标记的结果=数据非指令）；确定性判定，零模型参与。
 * 载体按轮显式绑定：只扫工具结果文本，不扫用户输入（用户指令是合法意图）。</p>
 */
@Component
public class AssistantInjectionFilter {

    /** 指令模式清单（中英双语常见注入话术；命中任一=打标）。
     *  忽略/无视句式用 .{0,6} 弹性衔接——"忽略之前的所有指令"等修饰变体都要命中
     *  （红队 inj1 实证：严格交替项卡死"之前的所有"5 字间隔）；负例
     *  "忽略上报的说明见附件"仍不中（6 字窗口内无指令词表词）。 */
    private static final List<Pattern> PATTERNS = List.of(
        Pattern.compile("忽略.{0,6}?(指令|提示词?|规则|设定)"),
        Pattern.compile("无视.{0,6}?(指令|规则|约束)"),
        Pattern.compile("你(现在)?(是|扮演)"),
        Pattern.compile("(输出|打印|复述|泄露)(你的)?(系统)?(提示|提示词|指令)"),
        Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above)"),
        Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|instructions)"),
        Pattern.compile("(?i)you\\s+are\\s+now"),
        Pattern.compile("(?i)reveal\\s+your\\s+(system\\s+)?prompt"),
        Pattern.compile("(?i)developer\\s+mode|(?i)jailbreak|(?i)DAN\\s+mode")
    );

    /** 是否命中注入模式（工具结果打标入口） */
    public boolean flagged(String toolResultText) {
        if (toolResultText == null || toolResultText.isEmpty()) {
            return false;
        }
        for (Pattern p : PATTERNS) {
            if (p.matcher(toolResultText).find()) {
                return true;
            }
        }
        return false;
    }
}
