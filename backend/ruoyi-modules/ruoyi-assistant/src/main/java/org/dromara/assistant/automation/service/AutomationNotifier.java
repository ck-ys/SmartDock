package org.dromara.assistant.automation.service;

import com.smartdock.dcs.domain.DcsNotice;
import com.smartdock.dcs.service.DcsNoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.automation.domain.DcsAutomation;
import org.springframework.stereotype.Component;

/**
 * 自动化结果通知（2026-11-05 题5 甲）。
 *
 * <p>执行线程与超时收口线程都要发通知，抽出来避免两处文案漂移。
 * 调度线程没有登录态，一律显式传租户（租户开关关闭时取不到 dynamic）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutomationNotifier {

    private static final int TITLE_LIMIT = 40;
    private static final int EXCERPT_LIMIT = 200;

    private final DcsNoticeService noticeService;

    /** 跑完了：正文摘一段结果，ref_url 直达结果会话 */
    public void success(DcsAutomation a, Long runId, String threadId, String answer) {
        String title = "自动化「" + truncate(a.getTitle(), TITLE_LIMIT) + "」跑完了";
        String content = excerpt(answer) + "\n点开查看完整结果。";
        try {
            noticeService.notifyIdem(a.getTenantId(), a.getUserId(), DcsNotice.TYPE_AUTOMATION, title, content,
                null, "automation-run:" + runId, "/agent?thread=" + threadId);
        } catch (Exception e) {
            log.warn("[Automation] 成功通知发送失败 run={}: {}", runId, e.toString());
        }
    }

    /** 失败了：正文说原因，ref_url 回自动化页手动重跑 */
    public void failure(DcsAutomation a, Long runId, String reason, boolean disabled) {
        String title = "自动化「" + truncate(a.getTitle(), TITLE_LIMIT) + "」运行失败";
        String content = reason + "\n可以到自动化页手动重跑。"
            + (disabled ? "\n已连续失败 " + AutomationSchedule.FAILURE_LIMIT + " 次，这条自动化已自动停用。" : "");
        try {
            noticeService.notifyIdem(a.getTenantId(), a.getUserId(), DcsNotice.TYPE_AUTOMATION, title, content,
                null, "automation-run:" + runId, "/agent/automation/" + a.getId());
        } catch (Exception e) {
            log.warn("[Automation] 失败通知发送失败 run={}: {}", runId, e.toString());
        }
    }

    /** 按码点截断：String.length/substring 会把 emoji 劈成半个（席3 P2-12） */
    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.codePointCount(0, s.length()) <= max
            ? s
            : s.substring(0, s.offsetByCodePoints(0, max)) + "…";
    }

    static String excerpt(String s) {
        if (s == null) {
            return "";
        }
        String flat = plain(s).replaceAll("\\s+", " ").trim();
        return truncate(flat, EXCERPT_LIMIT);
    }

    /**
     * 模型爱用 markdown，但通知正文和运行摘要都是纯文本位：
     * 抹掉 **粗体**、`代码`、行首 # 与小标题符号、[文字](链接) 的括号部分。
     * （不追求完整 markdown 解析，只把一眼看出是标记的字符去掉）
     */
    static String plain(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("!\\[[^\\]]*]\\([^)]*\\)", "");      // 图片整个丢掉（纯文本里没意义）
        t = t.replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1");          // [文字](链接) → 文字
        t = t.replaceAll("(\\*\\*|__|`)", "");
        t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        t = t.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        return t;
    }
}
