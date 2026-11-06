package org.dromara.assistant.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.core.AssistantAskUserBridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化反问工具（题12 案1，2026-10-31 拍板）：模型在候选选择/需要用户拍板时，
 * 用本工具把问题以卡片形式问出来（1-4 题、每题 2-4 个选项、可多选、可补充其它），
 * 用户点选后结果作为工具结果返回，模型接着干——不再靠纯文本猜"用户说的是哪个"。
 *
 * <p>只读工具（不改任何数据），任何角色可用；等待由 {@link AssistantAskUserBridge} 挂起，
 * run 保持 RUNNING、心跳照发；用户关页面/超时会明确告知模型没拿到答案。</p>
 */
@Slf4j
public class AskUserTools {

    static final ObjectMapper JSON = new ObjectMapper();

    private final AssistantAskUserBridge bridge;
    private final String tenantId;
    private final Long userId;
    private final String threadId;

    public AskUserTools(AssistantAskUserBridge bridge, String tenantId, Long userId, String threadId) {
        this.bridge = bridge;
        this.tenantId = tenantId;
        this.userId = userId;
        this.threadId = threadId;
    }

    @Tool(name = "ask_user",
        description = "向用户发起结构化反问（卡片选择），用户点完再继续。"
            + "什么时候用：需要用户在几个候选里选一个（哪条任务/哪个分群/哪段时间）、"
            + "或两个以上方案需要用户拍板、或关键参数缺失且猜不准。"
            + "什么时候别用：能查到的先查（别拿反问代替查询）；能用一句话问清的直接在正文里问。"
            + "questions 传 JSON 数组字符串，最多 4 题，每题 {question, header, options:[{label,description}], multiSelect}"
            + "（options 2-4 个，header 是短标签，multiSelect=true 表示可多选）。",
        readOnly = true)
    public String ask(
        @ToolParam(name = "questions",
            description = "JSON 数组字符串，例如 "
                + "[{\"question\":\"要催哪个任务？\",\"header\":\"选任务\",\"options\":"
                + "[{\"label\":\"期末成绩录入\",\"description\":\"还有 12 人未交\"},{\"label\":\"选课审核\"}],\"multiSelect\":false}]")
        String questionsJson) {

        List<Map<String, Object>> questions;
        try {
            questions = parseQuestions(questionsJson);
        } catch (IllegalArgumentException e) {
            return "反问没有发出去：" + e.getMessage() + "。请修正参数后重试，或改用正文直接提问。";
        }
        if (threadId == null || threadId.isBlank()) {
            return "当前会话不可用，无法发起结构化反问；请直接在正文里提问。";
        }
        // C 七行·提问自动继续：设置页开关随请求上行，这里按线程取值（缺省=开，ZCode 同款默认）
        boolean autoContinue = org.dromara.assistant.core.AssistantAskPrefHolder.isAutoContinue(threadId);
        Map<String, Object> result = bridge.ask(tenantId, userId, threadId, questions, autoContinue);
        return formatResult(result);
    }

    /** 解析并收紧参数（纯函数，单测直测）：非法即抛 IllegalArgumentException（人话原因） */
    public static List<Map<String, Object>> parseQuestions(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("questions 不能为空");
        }
        Object parsed;
        try {
            parsed = JSON.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("questions 不是合法 JSON");
        }
        if (!(parsed instanceof List<?> raw) || raw.isEmpty()) {
            throw new IllegalArgumentException("questions 要一个非空数组");
        }
        if (raw.size() > AssistantAskUserBridge.MAX_QUESTIONS) {
            throw new IllegalArgumentException("最多问 " + AssistantAskUserBridge.MAX_QUESTIONS + " 个问题");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> q)) {
                throw new IllegalArgumentException("每个问题必须是对象");
            }
            String question = str(q.get("question"), 200);
            if (question.isEmpty()) {
                throw new IllegalArgumentException("question 不能为空");
            }
            String header = str(q.get("header"), 20);
            boolean multi = Boolean.TRUE.equals(q.get("multiSelect"));
            List<Map<String, Object>> options = new ArrayList<>();
            java.util.Set<String> usedLabels = new java.util.HashSet<>();
            Object opts = q.get("options");
            if (opts instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> om) {
                        String label = dedupeLabel(str(om.get("label"), 40), usedLabels);
                        if (label.isEmpty()) {
                            continue;
                        }
                        Map<String, Object> one = new LinkedHashMap<>();
                        one.put("label", label);
                        String desc = str(om.get("description"), 80);
                        if (!desc.isEmpty()) {
                            one.put("description", desc);
                        }
                        options.add(one);
                    } else if (o != null) {
                        String label = dedupeLabel(str(o, 40), usedLabels);
                        if (!label.isEmpty()) {
                            options.add(Map.of("label", label));
                        }
                    }
                    if (options.size() >= AssistantAskUserBridge.MAX_OPTIONS) {
                        break;
                    }
                }
            }
            if (options.size() < 2) {
                throw new IllegalArgumentException("每题至少给 2 个选项（「其它」由卡片自带）");
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("question", question);
            one.put("header", header.isEmpty() ? "请选择" : header);
            one.put("options", options);
            one.put("multiSelect", multi);
            out.add(one);
        }
        return out;
    }

    /** 把桥的作答翻成模型能直接转述的文字（纯函数，单测直测） */
    public static String formatResult(Map<String, Object> result) {
        String action = result == null ? "" : String.valueOf(result.get("action"));
        if ("busy".equals(action)) {
            return "系统当前待作答的反问太多，这次没问出来；请改用正文直接提问。";
        }
        if ("timeout".equals(action)) {
            return "用户超时未作答（" + AssistantAskUserBridge.TIMEOUT_SECONDS
                + " 秒）。请用一句话说明你卡在哪、需要用户补充什么，等用户回复后再继续，不要重复发问。";
        }
        if (Boolean.TRUE.equals(result.get("auto"))) {
            return "用户 " + (AssistantAskUserBridge.AUTO_CONTINUE_SECONDS / 60)
                + " 分钟没有作答，已按「自动继续」往下走。请按最稳妥的默认继续；"
                + "涉及写操作或不可逆动作时停下来告诉用户缺哪项信息，不要重复发问。";
        }
        if ("cancelled".equals(action) || !"answered".equals(action)) {
            return "用户关掉了反问，没有作答。请按最稳妥的默认继续；涉及写操作或不可逆动作时停下来告诉用户缺哪项信息。";
        }
        Object answersRaw = result.get("answers");
        StringBuilder sb = new StringBuilder("用户已回答：");
        boolean any = false;
        if (answersRaw instanceof List<?> list) {
            for (Object a : list) {
                if (!(a instanceof Map<?, ?> m)) {
                    continue;
                }
                String header = str(m.get("header"), 20);
                if (header.isEmpty()) {
                    header = "问题";
                }
                List<String> labels = m.get("picked") instanceof List<?> pl
                    ? pl.stream().map(x -> str(x, AssistantAskUserBridge.MAX_LABEL_LEN))
                        .filter(x -> !x.isEmpty()).toList()
                    : List.of();
                String other = str(m.get("other"), AssistantAskUserBridge.MAX_OTHER_LEN);
                if (labels.isEmpty() && other.isEmpty()) {
                    continue;
                }
                any = true;
                sb.append("\n- ").append(header).append("：");
                if (!labels.isEmpty()) {
                    sb.append("选了「").append(String.join("」「", labels)).append("」");
                } else {
                    sb.append("未选预设项");
                }
                if (!other.isEmpty()) {
                    sb.append("；补充：").append(other);
                }
            }
        }
        return any ? sb.toString()
            : "用户没有给出具体选择。请按最稳妥的默认继续；涉及写操作或不可逆动作时停下来问用户缺哪项信息。";
    }

    /** 同题内重名选项追加序号（两条任务同名是常态；同名会让前端点一个亮两个、答案无法消歧） */
    private static String dedupeLabel(String base, java.util.Set<String> used) {
        if (base.isEmpty()) {
            return "";
        }
        String label = base;
        int n = 2;
        while (!used.add(label)) {
            String suffix = "（" + n++ + "）";
            String head = base.length() + suffix.length() > 40
                ? base.substring(0, Math.max(1, 40 - suffix.length()))
                : base;
            label = head + suffix;
        }
        return label;
    }

    private static String str(Object raw, int max) {
        if (raw == null) {
            return "";
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
