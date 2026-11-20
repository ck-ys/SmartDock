package com.smartdock.assistant.support;

import com.smartdock.assistant.domain.model.DigestResult;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆的常量与纯函数（批 6 题 2 案 1，2026-11-20 从 556 行记忆服务里抽出）。
 *
 * <p>为什么单独成件：拆分后新旧两侧都要用同一套判定——保存口（legacy 回合服务）与
 * 行内编辑（新根存储服务）共用 {@link #detectType} / {@link #topicKeyOf}，摘要与归档
 * 用 {@link #joinWithBudget} / {@link #archiveBefore}。纯静态、无状态、无依赖，单测直打。</p>
 *
 * <p>口径原样搬移（不改行为）：类型推断 team &gt; project &gt; user；同主题只认参数型偏好；
 * 相关性打分=中文二元组重叠 + 英文/数字词加权；摘要按字数预算截断（第一条必收）。</p>
 */
public final class AssistantMemoryFacts {

    private AssistantMemoryFacts() {
    }

    /** 单条上限（与表列宽一致） */
    public static final int MAX_CONTENT = 200;
    /** 每人容量上限（防模型滥存刷库；满=提示清理后再存） */
    public static final int MAX_PER_USER = 50;
    /** 每轮注入字数上限（题9 案1：条数上限≠注入量；示例值，可按经验调整） */
    public static final int MAX_DIGEST_CHARS = 800;
    /** 每轮最多注入条数（打分后 TopK） */
    public static final int MAX_DIGEST_ITEMS = 8;
    /** 归档阈值：90 天没被注入过 */
    public static final int ARCHIVE_DAYS = 90;

    /** 状态机（与 DDL 注释一致） */
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_SUPERSEDED = "SUPERSEDED";
    public static final String ST_ARCHIVED = "ARCHIVED";

    /** 参数型偏好主题：命中才做同主题覆盖（保守口径——事实类内容绝不自动覆盖） */
    private static final List<Map.Entry<String, Pattern>> TOPIC_RULES = List.of(
        // 「称呼」单独成词就够：模型常把「以后叫我老王」改写成「称呼用户为老王」，
        // 只认「叫我/称呼我」会漏判 → 同一个人并存两条互相矛盾的称呼（2026-11-12 活体抓到）
        Map.entry("call:name", Pattern.compile("叫我|称呼|喊我|我的名字|名字叫")),
        Map.entry("reply:lang", Pattern.compile("用中文|用英文|说中文|说英文|语言|英文回答|中文回答")),
        Map.entry("remind:default", Pattern.compile("催办|默认提醒|默认时间|提醒我")),
        Map.entry("time:zone", Pattern.compile("时区|工作日|上班时间|下班时间|工作时间|作息")));

    /** 类型推断规则（顺序即优先级：team > project > user；都不中→reference） */
    private static final Pattern TYPE_TEAM = Pattern.compile(
        "王工|张工|李工|赵工|刘工|同事|同学|主管|领导|负责人|负责人是|对接人|经理");
    private static final Pattern TYPE_PROJECT = Pattern.compile(
        "项目|出差|行程|周会|例会|会议|任务|截止|上线|部署|版本|迭代|需求|工单|排期");
    private static final Pattern TYPE_USER = Pattern.compile(
        "叫我|称呼|以后都|默认|偏好|喜欢|不喜欢|不要|别用|习惯|回复|回答|语气|风格");

    /** 打分：中文按二元组 + 英文/数字 token 重叠；停用字避免「的/了/是」刷分 */
    private static final Set<Character> STOP_CHARS = Set.of(
        '的', '了', '是', '在', '我', '你', '他', '她', '和', '与', '就', '都', '也', '吗', '呢',
        '把', '被', '给', '让', '这', '那', '有', '没', '不', '要', '会', '能', '个', '一');

    /**
     * 类型推断（题7）：team > project > user 顺序命中；都不中→reference。
     * 顺序有意为之——「周会由王工负责」应落 team（谁的事比什么事更具体）。
     */
    public static String detectType(String content) {
        if (content == null || content.isBlank()) {
            return "reference";
        }
        if (TYPE_TEAM.matcher(content).find()) {
            return "team";
        }
        if (TYPE_PROJECT.matcher(content).find()) {
            return "project";
        }
        if (TYPE_USER.matcher(content).find()) {
            return "user";
        }
        return "reference";
    }

    /**
     * 同主题键（题8）：只认参数型偏好（称呼/语言/催办默认/时区）。
     * 未命中返回 null=不做覆盖（事实类内容只做精确去重，绝不自动替换）。
     */
    public static String topicKeyOf(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        for (Map.Entry<String, Pattern> e : TOPIC_RULES) {
            if (e.getValue().matcher(content).find()) {
                return e.getKey();
            }
        }
        return null;
    }

    /**
     * 相关性打分（题9）：中文二元组重叠 + 英文/数字词重合。
     *
     * <p>可解释、无外部依赖：命中越多分越高；停用字剔除防「的/了/我」刷分。
     * 返回 0 = 与当前问题无关，不注入。</p>
     */
    public static int scoreOf(String content, String question) {
        if (content == null || content.isBlank() || question == null || question.isBlank()) {
            return 0;
        }
        Set<String> qGrams = grams(question);
        Set<String> cGrams = grams(content);
        int hit = 0;
        for (String g : qGrams) {
            if (cGrams.contains(g)) {
                hit++;
            }
        }
        // 英文/数字词额外加权（版本号、人名缩写这类专有词命中价值更高）
        int wordHit = 0;
        for (String w : words(question)) {
            if (content.toLowerCase().contains(w)) {
                wordHit += 2;
            }
        }
        return hit + wordHit;
    }

    /** 中文二元组（剔停用字与标点）；不足 2 字的片段按原样保留为 1 个 gram */
    public static Set<String> grams(String text) {
        Set<String> out = new LinkedHashSet<>();
        StringBuilder buf = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c) && !STOP_CHARS.contains(c)) {
                buf.append(Character.toLowerCase(c));
            } else {
                flushGrams(buf, out);
            }
        }
        flushGrams(buf, out);
        return out;
    }

    private static void flushGrams(StringBuilder buf, Set<String> out) {
        if (buf.length() == 0) {
            return;
        }
        String s = buf.toString();
        if (s.length() == 1) {
            out.add(s);
        } else {
            for (int i = 0; i + 1 < s.length(); i++) {
                out.add(s.substring(i, i + 2));
            }
        }
        buf.setLength(0);
    }

    /** 英文/数字词（长度≥2） */
    public static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[A-Za-z0-9_]{2,}").matcher(text);
        while (m.find()) {
            out.add(m.group().toLowerCase());
        }
        return out;
    }

    /**
     * 摘要拼装（题9 预算法，纯函数可单测）：按给定顺序取条，累加字数不超预算；
     * 第一条无论多长都收（至少一条），空列表返回 null。
     */
    public static DigestResult joinWithBudget(List<String> contents, List<Long> rowIds, int maxChars) {
        if (contents == null || contents.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<Long> ids = new ArrayList<>();
        int chars = 0;
        for (int i = 0; i < contents.size(); i++) {
            String c = contents.get(i);
            if (c == null || c.isEmpty()) {
                continue;
            }
            int add = c.length() + 3;
            if (chars > 0 && chars + add > maxChars) {
                break;
            }
            if (chars > 0) {
                sb.append('；');
            }
            sb.append('「').append(c).append('」');
            chars += add;
            if (rowIds != null && i < rowIds.size()) {
                ids.add(rowIds.get(i));
            }
        }
        if (sb.length() == 0) {
            return null;
        }
        return new DigestResult(sb.toString(), ids.size(), sb.length(), ids);
    }

    /** 归档截止时刻（题10 纯函数可单测）：now - days 天 */
    public static Date archiveBefore(Date now, int days) {
        return new Date(now.getTime() - days * 24L * 3600 * 1000);
    }
}
