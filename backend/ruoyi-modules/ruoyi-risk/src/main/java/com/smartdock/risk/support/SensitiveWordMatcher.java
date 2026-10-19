package com.smartdock.risk.support;

import cn.hutool.core.util.StrUtil;
import org.dromara.common.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Trie based sensitive word matcher for chat text.
 */
public class SensitiveWordMatcher {

    public static final String ACTION_MASK = "MASK";
    public static final String ACTION_BLOCK = "BLOCK";
    public static final String ACTION_REVIEW = "REVIEW";

    private final Node root = new Node();

    public SensitiveWordMatcher(List<WordRule> rules) {
        List<WordRule> sorted = rules == null ? List.of() : rules.stream()
            .filter(rule -> rule != null && StrUtil.isNotBlank(rule.word()))
            .sorted(Comparator
                .comparing((WordRule rule) -> !ACTION_BLOCK.equalsIgnoreCase(rule.action()))
                .thenComparing((WordRule rule) -> normalize(rule.word()).length(), Comparator.reverseOrder()))
            .toList();
        for (WordRule rule : sorted) {
            add(rule);
        }
    }

    public FilterResult filter(String content) {
        if (StrUtil.isBlank(content)) {
            return new FilterResult(content, List.of(), false);
        }
        StringBuilder masked = new StringBuilder(content);
        Set<String> hitWords = new LinkedHashSet<>();
        boolean review = false;

        for (Match best : scan(content)) {
            hitWords.add(best.rule.word());
            String action = StrUtil.blankToDefault(best.rule.action(), ACTION_MASK).toUpperCase(Locale.ROOT);
            if (ACTION_BLOCK.equals(action)) {
                throw new ServiceException("消息包含禁止发送内容，请修改后重试");
            }
            if (ACTION_REVIEW.equals(action)) {
                review = true;
            }
            for (int k = best.start; k < best.end; k++) {
                masked.setCharAt(k, '*');
            }
        }

        return new FilterResult(masked.toString(), new ArrayList<>(hitWords), review);
    }

    /**
     * 命中自测(2026-08-24): 不抛异常、不改写文本, 返回全部命中明细(词/动作/区间)与 blocked/review 标志。
     * <p>供敏感词管理页"命中测试"使用——管理员配完词库就地验证过滤效果;
     * 与 filter() 共用同一 scan 扫描语义(最长匹配+区间跳过), 结果即真实发送链路行为。</p>
     */
    public InspectionResult inspect(String content) {
        if (StrUtil.isBlank(content)) {
            return new InspectionResult(List.of(), false, false);
        }
        Map<String, Hit> dedup = new LinkedHashMap<>();
        boolean blocked = false;
        boolean review = false;
        for (Match best : scan(content)) {
            String action = StrUtil.blankToDefault(best.rule.action(), ACTION_MASK).toUpperCase(Locale.ROOT);
            if (ACTION_BLOCK.equals(action)) {
                blocked = true;
            }
            if (ACTION_REVIEW.equals(action)) {
                review = true;
            }
            dedup.putIfAbsent(best.rule.word(), new Hit(best.rule.word(), action, best.start, best.end));
        }
        return new InspectionResult(new ArrayList<>(dedup.values()), blocked, review);
    }

    /**
     * 归一化文本上的最长匹配扫描(每个起点取最长命中词, 命中后跳过区间)。
     * filter(掩码/阻断) 与 inspect(明细) 共用, 保证两套语义一致。
     */
    private List<Match> scan(String content) {
        String normalized = normalize(content);
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < normalized.length(); i++) {
            Node current = root;
            Match best = null;
            for (int j = i; j < normalized.length(); j++) {
                current = current.children.get(normalized.charAt(j));
                if (current == null) {
                    break;
                }
                if (current.rule != null) {
                    best = new Match(i, j + 1, current.rule);
                }
            }
            if (best == null) {
                continue;
            }
            matches.add(best);
            i = best.end - 1;
        }
        return matches;
    }

    private void add(WordRule rule) {
        String normalized = normalize(rule.word());
        if (StrUtil.isBlank(normalized)) {
            return;
        }
        Node current = root;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            current = current.children.computeIfAbsent(c, ignored -> new Node());
        }
        if (current.rule == null || isHigherPriority(rule, current.rule)) {
            current.rule = rule;
        }
    }

    private boolean isHigherPriority(WordRule candidate, WordRule current) {
        String candidateAction = StrUtil.blankToDefault(candidate.action(), ACTION_MASK).toUpperCase(Locale.ROOT);
        String currentAction = StrUtil.blankToDefault(current.action(), ACTION_MASK).toUpperCase(Locale.ROOT);
        return ACTION_BLOCK.equals(candidateAction) && !ACTION_BLOCK.equals(currentAction);
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == 12288) {
                c = ' ';
            } else if (c >= 65281 && c <= 65374) {
                c = (char) (c - 65248);
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    /**
     * 词规则: word=词面, action=MASK/BLOCK/REVIEW, scene=生效场景标记(CHAT/FORM/FILE 或 ALL)。
     * 匹配器本身不按场景过滤——场景过滤在规则装载层(DcsSensitiveWordFilterService.loadRules)完成;
     * scene 仅作为装载层的标记随规则携带, 便于调用方区分来源。
     */
    public record WordRule(String word, String action, String scene) {
        /** 兼容旧构造: 未指定场景时视为全场景(仅历史调用与测试使用) */
        public WordRule(String word, String action) {
            this(word, action, "ALL");
        }
    }

    public record FilterResult(String content, List<String> hitWords, boolean reviewRequired) {
    }

    /** 命中自测明细: word=命中原词, action=MASK/BLOCK/REVIEW, [start,end)=归一化文本区间 */
    public record Hit(String word, String action, int start, int end) {
    }

    /** 命中自测结果: hits 按首次命中去重; blocked=含阻断词(发送会被拦截); reviewRequired=含转复核词 */
    public record InspectionResult(List<Hit> hits, boolean blocked, boolean reviewRequired) {
    }

    private record Match(int start, int end, WordRule rule) {
    }

    private static class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private WordRule rule;
    }
}
