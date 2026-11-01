package org.dromara.test.assistant;

import org.dromara.assistant.tools.AskUserTools;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题12 案1（2026-10-31）：结构化反问的纯函数契约。
 *
 * <p>守三件事：①模型给的 JSON 收紧成前端可渲染的形状（题数/选项数封顶、header 兜底、
 * 空选项剔除）；②非法参数抛人话原因（工具层转成「反问没发出去」回给模型，不炸 run）；
 * ③四种收尾（已回答/超时/关掉/占满）翻成模型能直接转述的文字。</p>
 *
 * <p>纯函数单测（@Tag("local")）：不依赖 Spring、数据库与前端。</p>
 */
@Tag("local")
class AskUserToolsTest {

    @Test
    void parsesValidQuestionsAndDefaultsHeader() {
        List<Map<String, Object>> qs = AskUserTools.parseQuestions("""
            [{"question":"要催哪个任务？","options":[{"label":"期末录入","description":"还有 12 人未交"},{"label":"选课审核"}],"multiSelect":true},
             {"question":"什么时候发？","header":"时间","options":["今天","明天"]}]
            """);
        assertEquals(2, qs.size());
        assertEquals("请选择", qs.get(0).get("header"));
        assertEquals(Boolean.TRUE, qs.get(0).get("multiSelect"));
        assertEquals("时间", qs.get(1).get("header"));
        assertEquals(Boolean.FALSE, qs.get(1).get("multiSelect"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> opts = (List<Map<String, Object>>) qs.get(0).get("options");
        assertEquals(2, opts.size());
        assertEquals("期末录入", opts.get(0).get("label"));
        assertEquals("还有 12 人未交", opts.get(0).get("description"));
        // 纯字符串选项也归一成 {label}
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> opts2 = (List<Map<String, Object>>) qs.get(1).get("options");
        assertEquals(Map.of("label", "今天"), opts2.get(0));
    }

    @Test
    void capsOptionsAndDropsBlankLabels() {
        List<Map<String, Object>> qs = AskUserTools.parseQuestions("""
            [{"question":"选一个","options":[{"label":"A"},{"label":" "},{"label":"B"},{"label":"C"},{"label":"D"},{"label":"E"}]}]
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> opts = (List<Map<String, Object>>) qs.get(0).get("options");
        // 空 label 剔除后再封顶 4 个
        assertEquals(4, opts.size());
        assertEquals(List.of("A", "B", "C", "D"), opts.stream().map(o -> o.get("label")).toList());
    }

    @Test
    void rejectsIllegalArgumentsWithHumanReason() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AskUserTools.parseQuestions(null))
            .getMessage().contains("不能为空"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AskUserTools.parseQuestions("not json"))
            .getMessage().contains("不是合法 JSON"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AskUserTools.parseQuestions("[]"))
            .getMessage().contains("非空数组"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AskUserTools.parseQuestions("""
            [{"question":"q1","options":["a","b"]},{"question":"q2","options":["a","b"]},
             {"question":"q3","options":["a","b"]},{"question":"q4","options":["a","b"]},
             {"question":"q5","options":["a","b"]}]
            """)).getMessage().contains("最多问 4"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AskUserTools.parseQuestions("""
            [{"question":"选一个","options":["只有一个"]}]
            """)).getMessage().contains("至少给 2 个选项"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AskUserTools.parseQuestions("""
            [{"question":"  ","options":["a","b"]}]
            """)).getMessage().contains("question 不能为空"));
    }

    @Test
    void dedupesSameNamedOptions() {
        // 两条任务同名是常态：同名选项会让前端点一个亮两个、模型拿到无法消歧的答案
        List<Map<String, Object>> qs = AskUserTools.parseQuestions("""
            [{"question":"要催哪个任务？","options":["期末成绩录入","期末成绩录入","期末成绩录入"]}]
            """);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> opts = (List<Map<String, Object>>) qs.get(0).get("options");
        assertEquals(List.of("期末成绩录入", "期末成绩录入（2）", "期末成绩录入（3）"),
            opts.stream().map(o -> o.get("label")).toList());
    }

    @Test
    void formatsEmptyAnswersHonestly() {
        // 后端已拦「answer 但没有任何选择」，这里是纵深兜底：不假装拿到答案
        assertTrue(AskUserTools.formatResult(Map.of("action", "answered", "answers", List.of()))
            .contains("没有给出具体选择"));
        assertTrue(AskUserTools.formatResult(Map.of("action", "answered", "answers", List.of(
            Map.of("header", "时间", "picked", List.of(), "other", " "))))
            .contains("没有给出具体选择"));
        // 答案字段超长时按口径截断，不把整段原文塞进模型上下文
        String longLabel = "甲".repeat(80);
        String text = AskUserTools.formatResult(Map.of("action", "answered", "answers", List.of(
            Map.of("header", "时间", "picked", List.of(longLabel)))));
        assertTrue(text.contains("甲".repeat(40)) && !text.contains("甲".repeat(41)), text);
    }

    @Test
    void formatsAnsweredResultForModel() {
        String text = AskUserTools.formatResult(Map.of(
            "action", "answered",
            "answers", List.of(
                Map.of("header", "选任务", "question", "要催哪个？", "picked", List.of("期末录入", "选课审核"), "other", "优先期末"),
                Map.of("header", "时间", "question", "什么时候发？", "picked", List.of()))));
        assertTrue(text.contains("用户已回答"), text);
        assertTrue(text.contains("选任务：选了「期末录入」「选课审核」；补充：优先期末"), text);
        // 空作答条目整体丢弃（不拼「未选预设项」噪音）
        assertTrue(!text.contains("时间："), text);
    }

    @Test
    void formatsNonAnsweredOutcomesHonestly() {
        assertTrue(AskUserTools.formatResult(Map.of("action", "timeout")).contains("超时未作答"));
        assertTrue(AskUserTools.formatResult(Map.of("action", "cancelled")).contains("没有作答"));
        assertTrue(AskUserTools.formatResult(Map.of("action", "busy")).contains("没问出来"));
        // 未知/空结果按未作答处理（不假装拿到答案）
        assertTrue(AskUserTools.formatResult(null).contains("没有作答"));
    }
}
