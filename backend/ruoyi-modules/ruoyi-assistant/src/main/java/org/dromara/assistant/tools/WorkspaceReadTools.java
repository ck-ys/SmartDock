package org.dromara.assistant.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 助手工作区只读回读工具（2.0.4 驱逐中间件配套，2026-10-31 题7=案1）。
 *
 * <p>驱逐中间件把超大工具结果落盘到 {@code large_tool_results/<agent>/...}，
 * 上下文里只留头尾预览 + 路径。模型追问细节时用本工具按路径回读。</p>
 *
 * <p><b>边界</b>：根目录固定为助手工作区（不是代码仓，也不是用户文件区）；
 * 只读、不能写；拒绝绝对路径与 {@code ..} 逃逸，符号链接按真实路径再校验一次。
 * 只注册 {@code read_file} 一个名字——与驱逐中间件占位文案里的工具名对齐，
 * 否则模型会按提示词去调一个不存在的工具。</p>
 *
 * @author smartdock
 */
@Slf4j
public class WorkspaceReadTools {

    /** 默认一次读 400 行：足够回答追问，又不至于把上下文重新撑爆 */
    static final int DEFAULT_LIMIT = 400;
    /** 单次最多 2000 行 / 6 万字符；超了让模型用 offset 续读 */
    static final int MAX_LIMIT = 2_000;
    static final int MAX_CHARS = 60_000;

    private final Path root;

    public WorkspaceReadTools(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Tool(name = "read_file",
        description = "读取助手工作区里的文件（超大工具结果会自动归档到这里，上下文里只留路径）。"
            + "path 用相对路径，例如 large_tool_results/AGENT_ID/xxx.json；"
            + "文件很长时用 offset/limit 分页读，不要一次全读。",
        readOnly = true)
    public String readFile(
        @ToolParam(name = "path", description = "相对助手工作区的文件路径（如 large_tool_results/...）")
        String path,
        @ToolParam(name = "offset", description = "起始行（0 开始），默认 0", required = false)
        Integer offset,
        @ToolParam(name = "limit", description = "最多读多少行，默认 400，上限 2000", required = false)
        Integer limit) {

        if (path == null || path.isBlank()) {
            return "读取失败：path 不能为空（用 large_tool_results/ 下的相对路径）";
        }
        String raw = path.trim().replace('\\', '/');
        if (raw.startsWith("/") || raw.matches("^[A-Za-z]:.*")) {
            return "读取失败：只接受工作区内的相对路径，不接受绝对路径";
        }
        Path resolved = root.resolve(raw).normalize();
        if (!resolved.startsWith(root)) {
            return "读取失败：路径越出了助手工作区（不允许 .. 或符号链接逃逸）";
        }
        try {
            Path realRoot = root.toRealPath();
            Path real = resolved.toRealPath();
            if (!real.startsWith(realRoot)) {
                return "读取失败：路径越出了助手工作区（符号链接指向工作区外）";
            }
            if (!Files.isRegularFile(real)) {
                return "读取失败：不是文件或不存在（" + raw + "）";
            }
            int off = offset == null || offset < 0 ? 0 : offset;
            int lim = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
            return readLines(real, off, lim);
        } catch (IOException e) {
            log.debug("[WorkspaceRead] 读取失败 path={}: {}", raw, e.getMessage());
            return "读取失败：文件不存在或不可读（" + raw + "）";
        } catch (Exception e) {
            log.debug("[WorkspaceRead] 路径校验失败 path={}: {}", raw, e.getMessage());
            return "读取失败：路径不合法（" + raw + "）";
        }
    }

    private String readLines(Path file, int offset, int limit) throws IOException {
        StringBuilder out = new StringBuilder();
        int read = 0;
        boolean cut = false;
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            var it = lines.skip(offset).iterator();
            while (it.hasNext() && read < limit && out.length() < MAX_CHARS) {
                String line = it.next();
                // 单行超长（业务工具返回的紧凑 JSON 常见一行几十万字符）：必须按字符预算截断，
                // 否则一次回读就把刚驱逐的结果整份塞回上下文，驱逐机制形同虚设
                int budget = Math.max(0, MAX_CHARS - out.length());
                if (line.length() > budget) {
                    line = line.substring(0, budget);
                    cut = true;
                }
                out.append(String.format("%6d\t%s%n", offset + read + 1, line));
                read++;
                if (cut) {
                    break;
                }
            }
        }
        if (read == 0) {
            return "（从第 " + offset + " 行起没有内容：文件比这更短，或 offset 越界）";
        }
        if (cut) {
            out.append("…（本行超长，已截断到 ").append(MAX_CHARS)
                .append(" 字符；单行文件无法按行分页，请按需读其它归档文件）\n");
        } else if (read >= limit || out.length() >= MAX_CHARS) {
            out.append("…（本次读到第 ").append(offset + read)
                .append(" 行；继续读用 offset=").append(offset + read).append("）\n");
        }
        return out.toString();
    }
}
