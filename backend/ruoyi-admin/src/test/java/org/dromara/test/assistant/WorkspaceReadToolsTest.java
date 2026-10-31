package org.dromara.test.assistant;

import org.dromara.assistant.tools.WorkspaceReadTools;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 题7 案1（2026-10-31）：驱逐落盘后的工作区只读回读契约。
 *
 * <p>守四件事：①相对路径能读、带行号；②offset/limit 分页 + 续读提示；
 * ③越界一律拒（绝对路径 / .. / 符号链接逃逸 / 目录）；④读不到给人话，不给异常。</p>
 *
 * <p>纯函数单测（@Tag("local")）：只碰临时目录，不依赖 Spring 与数据库。</p>
 */
@Tag("local")
class WorkspaceReadToolsTest {

    private static WorkspaceReadTools tools(Path root) {
        return new WorkspaceReadTools(root);
    }

    @Test
    void readsRelativeFileWithLineNumbers(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("large_tool_results/agent-a"));
        Path f = root.resolve("large_tool_results/agent-a/report.txt");
        Files.writeString(f, "alpha\nbeta\ngamma\n", StandardCharsets.UTF_8);

        String out = tools(root).readFile("large_tool_results/agent-a/report.txt", null, null);

        assertTrue(out.contains("alpha"), "应读到正文: " + out);
        assertTrue(out.contains("1\talpha") || out.contains(" 1\talpha"), "应带行号: " + out);
        assertTrue(out.contains("3\tgamma") || out.contains(" 3\tgamma"), "行号应连续: " + out);
        assertFalse(out.contains("继续读"), "文件读完不该给续读提示: " + out);
    }

    @Test
    void paginatesWithOffsetAndLimit(@TempDir Path root) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("line-").append(i).append('\n');
        }
        Path f = root.resolve("big.txt");
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);

        String first = tools(root).readFile("big.txt", 0, 5);
        assertTrue(first.contains("line-1"), first);
        assertTrue(first.contains("line-5"), first);
        assertFalse(first.contains("line-6"), "limit=5 不该读到第 6 行: " + first);
        assertTrue(first.contains("offset=5"), "应提示下一段 offset: " + first);

        String second = tools(root).readFile("big.txt", 5, 5);
        assertTrue(second.contains("line-6"), second);
        assertTrue(second.contains("offset=10"), second);

        String beyond = tools(root).readFile("big.txt", 100, 5);
        assertTrue(beyond.contains("没有内容"), "越界 offset 给人话: " + beyond);
    }

    @Test
    void rejectsEscapesAndInvalidTargets(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/ok.txt"), "ok", StandardCharsets.UTF_8);
        Files.writeString(root.getParent().resolve("outside.txt"), "secret", StandardCharsets.UTF_8);

        WorkspaceReadTools t = tools(root);
        assertTrue(t.readFile("../outside.txt", null, null).contains("越出"), ".. 逃逸必须拒");
        assertTrue(t.readFile("/etc/passwd", null, null).contains("绝对路径"), "POSIX 绝对路径必须拒");
        assertTrue(t.readFile("C:\\Windows\\win.ini", null, null).contains("绝对路径"), "Windows 绝对路径必须拒");
        assertTrue(t.readFile("", null, null).contains("不能为空"), "空 path 给人话");
        assertTrue(t.readFile("sub", null, null).contains("不是文件"), "目录不给读");
        assertTrue(t.readFile("nope.txt", null, null).contains("读取失败"), "不存在给人话");

        // 大小写/混杂分隔符归一后仍能读
        assertTrue(t.readFile("sub\\ok.txt", null, null).contains("ok"), "反斜杠路径应归一后可读");
    }

    @Test
    void rejectsSymlinkEscape(@TempDir Path root) throws Exception {
        Path outside = root.getParent().resolve("link-target.txt");
        Files.writeString(outside, "secret", StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), outside);
        } catch (Exception e) {
            // Windows 非开发者模式无符号链接权限：跳过本断言（越界主闸仍由 .. / 绝对路径守）
            return;
        }
        String out = tools(root).readFile("link.txt", null, null);
        assertEquals(true, out.contains("越出"), "符号链接指向工作区外必须拒: " + out);
    }

    /** 题7 三席回修 P1：单行超长（紧凑 JSON 常见一行几十万字符）必须按字符预算截断——
     *  不截=一次回读把刚驱逐的结果整份塞回上下文，驱逐机制形同虚设。 */
    @Test
    void truncatesSingleHugeLine(@TempDir Path root) throws Exception {
        StringBuilder sb = new StringBuilder(200_000);
        for (int i = 0; i < 200_000; i++) {
            sb.append('a');
        }
        Files.writeString(root.resolve("huge.json"), sb.toString(), StandardCharsets.UTF_8);

        String out = tools(root).readFile("huge.json", null, null);

        assertTrue(out.length() <= 60_500, "输出必须被 6 万字符预算封顶，实际=" + out.length());
        assertTrue(out.contains("本行超长，已截断"), "应明确告知本行被截断: " + out.substring(0, Math.min(200, out.length())));
        assertFalse(out.contains("继续读用 offset="), "单行文件不能承诺按行续读");
    }

}
