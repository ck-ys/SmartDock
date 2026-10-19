package org.dromara.assistant.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 助手 PII 确定性过滤器（批G G4，2026-10-06；配方=P15c 探针已证毕的正则三件+姓名字典法）。
 *
 * <p>四要素（spec §6 X-08）：证件号/手机号/邮箱走正则（含全角数字归一化，仅候选窗口内
 * 折叠——全局折叠会误伤日期区间，15c 定论）；姓名走字典法（中文常用字正则误伤率高，
 * 生产=实体字段元数据驱动——P1 取本租户 sys_user 昵称，5 分钟缓存，长名优先替换）。
 * 掩码形态：手机 138****0000、邮箱 a**@域、证件 前6********末4、姓名 首+*。</p>
 *
 * <p>挂点（四入口，spec §6）：①用户输入出网前 + ②工具结果进上下文前（ToolSupport /
 * AssistantPiiModelDecorator 确定性调用）；③记忆写入 P1 未装配（拍板⑥恒关，测试断言零调用）；
 * ④模型输出渲染前=落库侧脱敏 + 出网侧 Model 装饰器（流式逐帧掩码的跨帧残留登记 P2）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantPiiFilter {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /**
     * 数字候选窗口（15c 配方；dspro P1-3 修：中间类必须含全角空格 U+3000/全角连字符
     * U+FF0D/en dash U+2013/制表换行 \s——只认 ASCII 分隔会让 Word/微信粘贴的
     * "138－0013－8000" 整类明文漏出网）。窗口内归一化后整体匹配手机/证件
     * （matches() 全匹配防截取误伤；业务长编号 19 位不中窗内形态=原样放行）。
     */
    private static final Pattern DIGIT_WINDOW =
        Pattern.compile("(?<![0-9０-９])[0-9０-９][0-9０-９ \\-+　－–\\s]{6,28}[0-9０-９][xX]?(?![0-9０-９xX])");

    /** 归一化剔除集：ASCII 空白/连字符 + 全角空格/全角连字符/en dash（与窗口类同集） */
    private static final String SEPARATOR_STRIP = "[\\s\\u3000\\uFF0D\\u2013-]";

    /** 姓名字典缓存：tenant → (名单, 加载时刻)，5 分钟 */
    private static final long DICT_TTL_MS = 300_000L;
    private final Map<String, DictEntry> dictCache = new ConcurrentHashMap<>();

    private record DictEntry(List<String> names, long at) {
    }

    private final JdbcTemplate jdbcTemplate;

    /**
     * 全文脱敏（出网/落库口径）。原文不含 PII 时原串返回（零拷贝快路径）。
     *
     * @param tenantId 租户（姓名字典按租户取；异步线程无租户上下文，必须显式传）
     */
    public String mask(String text, String tenantId) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = maskDigitWindows(text);
        out = maskPattern(out, EMAIL, AssistantPiiFilter::maskEmail);
        out = maskNames(out, tenantNames(tenantId));
        return out;
    }

    /** 掩码后修复（批J-3 E2E 实锤）：掩码落在 JSON 数字字面量（userId:9200000**1）会产出
     *  非法 JSON，前端 parseToolJson 双路全挂 → DataCard 不渲染——值位置含 * 的「数字」
     *  补引号转字符串。
     *  <p>批J 三审 P1-1（glm53+dspro 双席实证）：字符串值内的「冒号+掩码数字+定界」
     *  （如 "备注":"联系人:138****0000,尽快"）是合法 JSON，裸正则会往字符串中间插引号
     *  反而打坏——双防线：①先探针解析，合法一律原样返回；②正则收紧为键值冒号
     *  （冒号前必须有字符串键尾引号）。</p> */
    private static final java.util.regex.Pattern MASKED_JSON_NUMBER =
        java.util.regex.Pattern.compile("(\"\\s*:\\s*)(-?\\d[\\d*]*\\*[\\d*]*)(?=\\s*[,}\\]])");

    private static final com.fasterxml.jackson.databind.ObjectMapper REPAIR_PROBE =
        new com.fasterxml.jackson.databind.ObjectMapper();

    public static String repairMaskedJsonNumbers(String json) {
        if (json == null || json.indexOf('*') < 0) {
            return json;
        }
        try {
            REPAIR_PROBE.readTree(json);
            return json;
        } catch (Exception ignore) {
            // 掩码确实打坏了 JSON 才走修复
        }
        return MASKED_JSON_NUMBER.matcher(json).replaceAll("$1\"$2\"");
    }

    /** 是否含 PII（打标用，避免无谓重建字符串） */
    public boolean containsPii(String text, String tenantId) {
        return text != null && !mask(text, tenantId).equals(text);
    }

    private String maskPattern(String text, Pattern pattern, UnaryOperator<String> masker) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        m.reset();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(masker.apply(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 数字窗口脱敏：窗口归一化（全角→半角+去空格连字符）后整体匹配手机/证件才掩码；
     *  全部窗口都只是候选（长编号等）时原串直通（零拷贝，G4 实证 appendReplacement 会重建串） */
    private String maskDigitWindows(String text) {
        Matcher m = DIGIT_WINDOW.matcher(text);
        if (!m.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        boolean changed = false;
        m.reset();
        while (m.find()) {
            String raw = m.group();
            String norm = normalizeFullwidth(raw).replaceAll(SEPARATOR_STRIP, "");
            String masked = null;
            if (PHONE.matcher(norm).matches()) {
                masked = maskPhone(norm);
            } else if (ID_CARD.matcher(norm).matches() && !raw.matches(".*[\\s\\u3000].*")) {
                // 证件分支防拼接误伤（dspro P2-10）：窗口把两个带空格分隔的普通数字
                // （如"123456789 123456789"归一后恰 18 位）并成假证件——真证件号粘贴
                // 不含空白，含空白的 18 位一律放行
                masked = maskIdCard(norm);
            }
            if (masked != null) {
                changed = true;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(masked != null ? masked : raw));
        }
        m.appendTail(sb);
        return changed ? sb.toString() : text;
    }

    /** 姓名掩码：长名优先（张三丰 先于 张三），避免短名截断长名 */
    private String maskNames(String text, List<String> names) {
        String out = text;
        for (String name : names) {
            if (name != null && name.length() >= 2 && out.contains(name)) {
                out = out.replace(name, name.charAt(0) + "*".repeat(name.length() - 1));
            }
        }
        return out;
    }

    /** 本租户姓名字典（glm53 P1-1/dsflash 同源修：真名在 nick_name——张三丰们
     *  的 user_name 是工号；user_name 双列并入兼容个别环境把中文名放登录名的，
     *  UNION 去重后仍按 2-4 字过滤；失败按空字典降级） */
    private List<String> tenantNames(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        DictEntry entry = dictCache.computeIfAbsent(tenantId, k -> new DictEntry(List.of(), 0L));
        if (now - entry.at() > DICT_TTL_MS) {
            // 到期刷新（dspro P2-5 顺手修）：computeIfAbsent 占位防并发风暴，查库失败沿用旧字典
            List<String> names;
            try {
                names = new ArrayList<>(jdbcTemplate.query(
                    "SELECT DISTINCT nm FROM ("
                        + " SELECT nick_name AS nm FROM sys_user WHERE tenant_id=? AND del_flag='0'"
                        + " UNION SELECT user_name AS nm FROM sys_user WHERE tenant_id=? AND del_flag='0'"
                        + ") t WHERE char_length(nm) BETWEEN 2 AND 4",
                    (rs, i) -> rs.getString(1), tenantId, tenantId));
            } catch (Exception e) {
                log.warn("[AssistantPii] 姓名字典加载失败(沿用旧字典) tenant={}: {}", tenantId, e.getMessage());
                return entry.names();
            }
            entry = new DictEntry(sortedByLengthDesc(names), now);
            dictCache.put(tenantId, entry);
        }
        return entry.names();
    }

    private static List<String> sortedByLengthDesc(List<String> names) {
        names.sort((a, b) -> b.length() - a.length());
        return names;
    }

    static String maskPhone(String phone) {
        String digits = normalizeFullwidth(phone);
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }

    static String maskIdCard(String id) {
        return id.substring(0, 6) + "********" + id.substring(id.length() - 4);
    }

    static String maskEmail(String email) {
        int at = email.indexOf('@');
        return email.charAt(0) + "**" + email.substring(at);
    }

    private static String normalizeFullwidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 测试注入字典用（生产禁用） */
    void putDictForTest(String tenantId, List<String> names) {
        dictCache.put(tenantId, new DictEntry(sortedByLengthDesc(new ArrayList<>(names)), System.currentTimeMillis()));
    }
}
