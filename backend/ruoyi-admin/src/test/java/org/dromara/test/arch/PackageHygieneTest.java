package org.dromara.test.arch;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包卫生门禁（2026-11-16 拍板 Q1-2 / Q3-1 / Q4-1 / Q6-1，2026-11-17 落地）。
 *
 * <p>五条规矩，前 2/3/5 条「只减不增」，第 4 条零容忍：</p>
 * <ol>
 *   <li><b>行内全限定名</b>：源码行里出现 {@code org.dromara.} / {@code com.smartdock.} /
 *       {@code java.util.} 的整条包名（import 行、注释、字符串不算）→ 失败。真需要时在行尾写
 *       {@code // arch-ignore-fqn} 并说明原因。</li>
 *   <li><b>接口出参 Map</b>：Controller 方法返回 {@code R<Map<String, Object>>}（含 List 包一层）
 *       → 失败；契约要强类型 VO。</li>
 *   <li><b>legacy 根包不再长文件</b>：{@code org.dromara.assistant} 下的 .java 文件数只减不增
 *       （Q6-1：新代码只进 {@code com.smartdock.assistant}）。</li>
 *   <li><b>domain 不 import 上层</b>（2026-11-17 批 1 规则 7）：{@code com.smartdock.*} 的 domain 包
 *       不许 import service / controller / infrastructure / mapper——实体要编排去 service，
 *       跨层收口建 {@code domain/gateway} 接口、{@code infrastructure} 实现。底座（org.dromara.common 等）不算上层。</li>
 *   <li><b>新根 service 对 legacy 只减不增</b>（2026-11-17 批 1 规则 8）：{@code com.smartdock.*} 的 service 包
 *       import {@code org.dromara.assistant.*}（legacy 助手根）只减不增；清零后段删掉、零容忍。</li>
 * </ol>
 *
 * <p><b>豁免清单</b>：{@code src/test/resources/arch-baseline.json}。清单里有的不算新违规；
 * <b>清单里剩着但代码已经改掉的也算失败</b>——修掉一条就必须删一行，防止清单腐烂。
 * 需要整份刷新时（例如大搬迁后）：{@code ARCH_BASELINE_UPDATE=1 mvn -pl ruoyi-admin -P local test -Dtest=PackageHygieneTest}。</p>
 *
 * <p>扫描范围：{@code backend/**}{@code /src/main/java/**}{@code /*.java}（生产代码）；测试代码不在此列。</p>
 */
@Tag("local")
class PackageHygieneTest {

    /** 项目自有根包 + java.util：这三类全限定写法没有技术必要，一律要求 import */
    private static final Pattern INLINE_FQN = Pattern.compile(
        "\\b(?:org\\.dromara|com\\.smartdock|java\\.util)(?:\\.[a-z][a-z0-9_]*)*\\.[A-Z][A-Za-z0-9_$]*");

    /** Controller 出参：R<...Map<String, Object>...> 或 TableDataInfo<...> */
    private static final Pattern CONTROLLER_MAP_RETURN = Pattern.compile(
        "\\b(?:R|TableDataInfo)\\s*<[^>]*\\bMap\\s*<\\s*String\\s*,\\s*Object\\s*>");

    private static final Pattern ARCH_IGNORE = Pattern.compile("//\\s*arch-ignore-fqn\\b");

    /** 规则 7：domain 包不许 import 上层；只盯本仓两根（org.dromara.assistant / com.smartdock），底座不算上层。
     *  禁段可以紧贴根（org.dromara.assistant.service.X）也可以隔任意层包段（com.smartdock.assistant.service.X）。 */
    private static final Pattern DOMAIN_UPPER_IMPORT = Pattern.compile(
        "^import\\s+(?:static\\s+)?(?:org\\.dromara\\.assistant|com\\.smartdock)\\.(?:[a-z0-9_]+\\.)*"
            + "(?:service|controller|infrastructure|mapper)\\.");

    /** 规则 8：legacy 助手根（org.dromara.assistant）的任何 import */
    private static final Pattern LEGACY_ASSISTANT_IMPORT = Pattern.compile(
        "^import\\s+(?:static\\s+)?org\\.dromara\\.assistant\\.");

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 数组一项一行：586 条的清单要能逐行 diff / 逐行删，不能挤成一条长线 */
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
        .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
    private static final boolean UPDATE = "1".equals(System.getenv("ARCH_BASELINE_UPDATE"));

    @Test
    void 源码行内不许写整条包名() throws IOException {
        Path backend = backendRoot();
        List<Path> files = javaFiles(backend);
        Map<String, Integer> actual = new TreeMap<>();
        Map<String, List<String>> samples = new TreeMap<>();

        for (Path file : files) {
            String rel = rel(backend, file);
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String[] rawLines = raw.split("\n", -1);
            String[] codeLines = stripCommentsAndStrings(raw).split("\n", -1);
            for (int i = 0; i < codeLines.length; i++) {
                String trimmed = codeLines[i].trim();
                if (trimmed.isEmpty() || trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                    continue;
                }
                if (ARCH_IGNORE.matcher(rawLines[i]).find()) {
                    continue;
                }
                Matcher m = INLINE_FQN.matcher(codeLines[i]);
                while (m.find()) {
                    actual.merge(rel + "|" + m.group(), 1, Integer::sum);
                    samples.computeIfAbsent(rel + "|" + m.group(), k -> new ArrayList<>())
                        .add(rel + ":" + (i + 1));
                }
            }
        }

        // 防「源码目录定位错了，扫了个空集」：文件数是结构不变量，不随清理下降
        assertThat(files.size())
            .as("只扫到 %d 个 src/main/java 文件（当前违规 %d 种），源码目录定位错了", files.size(), actual.size())
            .isGreaterThan(500);
        assertOnlyDecreases("inlineFqn", flatten(actual), samples, "行内全限定名（改成 import）");
    }

    @Test
    void Controller出参不许是Map() throws IOException {
        Path backend = backendRoot();
        Map<String, Integer> actual = new TreeMap<>();
        Map<String, List<String>> samples = new TreeMap<>();

        for (Path file : javaFiles(backend)) {
            String rel = rel(backend, file);
            if (!rel.endsWith("Controller.java")) {
                continue;
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String[] rawLines = raw.split("\n", -1);
            String[] codeLines = stripCommentsAndStrings(raw).split("\n", -1);
            for (int i = 0; i < codeLines.length; i++) {
                if (CONTROLLER_MAP_RETURN.matcher(codeLines[i]).find()) {
                    actual.merge(rel, 1, Integer::sum);
                    samples.computeIfAbsent(rel, k -> new ArrayList<>()).add(rel + ":" + (i + 1));
                }
            }
        }

        assertOnlyDecreases("controllerMapReturn", flatten(actual), samples, "Controller 返回 Map（改成强类型 VO）");
    }

    @Test
    void legacy根包不再新增文件() throws IOException {
        Path backend = backendRoot();
        Path legacy = backend.resolve("ruoyi-modules/ruoyi-assistant/src/main/java/org/dromara/assistant");
        assertThat(legacy).as("legacy 根包目录必须存在：%s", legacy).isDirectory();

        int count;
        try (Stream<Path> walk = Files.walk(legacy)) {
            count = (int) walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p)).count();
        }

        ObjectNode baseline = readBaseline(backend);
        if (UPDATE) {
            baseline.put("legacyOrgAssistantFiles", count);
            writeBaseline(backend, baseline);
            System.out.println("[arch-baseline] legacyOrgAssistantFiles = " + count);
            return;
        }
        assertThat(baseline.has("legacyOrgAssistantFiles"))
            .as("arch-baseline.json 缺 legacyOrgAssistantFiles；用 ARCH_BASELINE_UPDATE=1 生成")
            .isTrue();
        int expected = baseline.get("legacyOrgAssistantFiles").asInt();
        assertThat(count)
            .as("org.dromara.assistant 下 .java 文件数只减不增（Q6-1：新代码只进 com.smartdock.assistant）。"
                + "当前 %d，基线 %d。删了文件请把基线数字一起调小。", count, expected)
            .isEqualTo(expected);
    }

    @Test
    void domain包不许import上层() throws IOException {
        Path backend = backendRoot();
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(backend)) {
            String rel = rel(backend, file);
            String unix = "/" + rel;
            if (!unix.contains("/com/smartdock/") || !unix.contains("/domain/")) {
                continue;
            }
            for (String line : Files.readString(file, StandardCharsets.UTF_8).split("\n", -1)) {
                String s = line.trim();
                if (!s.startsWith("import ")) {
                    continue;
                }
                if (DOMAIN_UPPER_IMPORT.matcher(s).find()) {
                    violations.add(rel + ": " + s);
                }
            }
        }
        assertThat(violations)
            .as("domain 包不许 import 上层（service / controller / infrastructure / mapper，规则 7）。"
                + "实体要编排去 service；跨层收口建 domain/gateway 接口、infrastructure 实现")
            .isEmpty();
    }

    @Test
    void 新根service对legacy只减不增() throws IOException {
        Path backend = backendRoot();
        Map<String, Integer> actual = new TreeMap<>();
        Map<String, List<String>> samples = new TreeMap<>();
        for (Path file : javaFiles(backend)) {
            String rel = rel(backend, file);
            String unix = "/" + rel;
            if (!unix.contains("/com/smartdock/") || !unix.contains("/service/") || unix.contains("/infrastructure/")) {
                continue;
            }
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String s = lines[i].trim();
                if (!s.startsWith("import ")) {
                    continue;
                }
                if (LEGACY_ASSISTANT_IMPORT.matcher(s).find()) {
                    String fqn = s.replaceFirst("^import\\s+(?:static\\s+)?", "").replace(";", "").trim();
                    actual.merge(rel + "|" + fqn, 1, Integer::sum);
                    samples.computeIfAbsent(rel + "|" + fqn, k -> new ArrayList<>()).add(rel + ":" + (i + 1));
                }
            }
        }
        if (actual.isEmpty()) {
            // 清零态：段必须已删（残留即失败，防清单腐烂）；段不存在则零容忍通过
            ObjectNode baseline = readBaseline(backend);
            assertThat(baseline.has("legacyImportsFromNewRoot"))
                .as("新根 service 对 legacy import 已清零——请删掉 arch-baseline.json 里 legacyImportsFromNewRoot 段（规则 8）")
                .isFalse();
            return;
        }
        // 非清零 + 段不存在 = 新增违规。给零容忍专属文案，绝不指向 ARCH_BASELINE_UPDATE
        // （那会把违规整段写回基线=自拆门禁，多审席 B P1）
        ObjectNode baseline = readBaseline(backend);
        assertThat(baseline.has("legacyImportsFromNewRoot"))
            .as("规则 8 已清零删段，零容忍：新根 service 又 import 了 legacy org.dromara.assistant（%d 处，例如 %s）。"
                + "改法是经 domain/gateway 收口、实现下沉 infrastructure；【禁止】用 ARCH_BASELINE_UPDATE=1 生成该段把违规合法化",
                actual.size(), samples.values().iterator().next().get(0))
            .isTrue();
        assertOnlyDecreases("legacyImportsFromNewRoot", flatten(actual), samples,
            "新根 service import legacy org.dromara.assistant（规则 8：经 domain/gateway 收口，实现下沉 infrastructure）");
    }

    // ------------------------------------------------------------------
    // 只减不增比对
    // ------------------------------------------------------------------

    /**
     * @param section   arch-baseline.json 里的段名
     * @param actual    当前违规（key → 次数）
     * @param samples   key → 「文件:行」样例，用于失败信息定位
     * @param what      人话标题
     */
    private static void assertOnlyDecreases(String section, List<String> actual,
                                            Map<String, List<String>> samples, String what) throws IOException {
        Path backend = backendRoot();
        ObjectNode baseline = readBaseline(backend);
        if (UPDATE) {
            ArrayNode arr = baseline.putArray(section);
            actual.forEach(arr::add);
            writeBaseline(backend, baseline);
            System.out.println("[arch-baseline] " + section + " = " + actual.size() + " 条");
            return;
        }

        assertThat(baseline.has(section))
            .as("arch-baseline.json 缺 %s 段；用 ARCH_BASELINE_UPDATE=1 生成", section)
            .isTrue();
        Map<String, Integer> frozen = new TreeMap<>();
        for (JsonNode n : baseline.get(section)) {
            String key = n.asText();
            int idx = key.lastIndexOf('|');
            frozen.put(idx < 0 ? key : key.substring(0, idx), idx < 0 ? 1 : Integer.parseInt(key.substring(idx + 1)));
        }

        List<String> added = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        Map<String, Integer> seen = new TreeMap<>();
        for (String key : actual) {
            int idx = key.lastIndexOf('|');
            String base = key.substring(0, idx);
            int count = Integer.parseInt(key.substring(idx + 1));
            seen.put(base, count);
            int allowed = frozen.getOrDefault(base, 0);
            if (count > allowed) {
                added.add(base + "（多 " + (count - allowed) + " 处，例如 " + samples.get(base).get(0) + "）");
            }
        }
        for (Map.Entry<String, Integer> e : frozen.entrySet()) {
            int now = seen.getOrDefault(e.getKey(), 0);
            if (now < e.getValue()) {
                stale.add(e.getKey() + "（基线 " + e.getValue() + "，现在 " + now + "）");
            }
        }

        assertThat(added)
            .as("%s：发现新违规（只减不增）。改成 import / 强类型 VO；确无替代写法时行尾加 // arch-ignore-fqn", what)
            .isEmpty();
        assertThat(stale)
            .as("%s：基线里的条目已经改好了——请删掉 arch-baseline.json 里对应的行（清单禁止残留）", what)
            .isEmpty();
    }

    /** 排序后的 "key|count" 列表，便于 diff 与人工比对 */
    private static List<String> flatten(Map<String, Integer> map) {
        List<String> out = new ArrayList<>();
        map.forEach((k, v) -> out.add(k + "|" + v));
        return out;
    }

    // ------------------------------------------------------------------
    // 文件与基线
    // ------------------------------------------------------------------

    private static Path backendRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null) {
            if (Files.isDirectory(p.resolve("ruoyi-modules")) && Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("找不到 backend 根目录（从 " + Paths.get("").toAbsolutePath() + " 向上没看到 ruoyi-modules/pom.xml）");
    }

    private static List<Path> javaFiles(Path backend) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(backend)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                .forEach(files::add);
        }
        return files;
    }

    private static String rel(Path backend, Path file) {
        return backend.relativize(file).toString().replace('\\', '/');
    }

    private static ObjectNode readBaseline(Path backend) throws IOException {
        Path file = baselineFile(backend);
        if (!Files.isRegularFile(file)) {
            return JSON.createObjectNode();
        }
        JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        return node.isObject() ? (ObjectNode) node : JSON.createObjectNode();
    }

    private static void writeBaseline(Path backend, ObjectNode node) throws IOException {
        Path file = baselineFile(backend);
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writer(PRETTY).writeValueAsString(node) + "\n",
            StandardCharsets.UTF_8);
    }

    private static Path baselineFile(Path backend) {
        return backend.resolve("ruoyi-admin/src/test/resources/arch-baseline.json");
    }

    /**
     * 去掉注释与字符串内容（保留换行，行号不变）。注释里提包名是合法的，
     * 字符串里出现 "org.foo.Bar" 也不该算违规。
     */
    static String stripCommentsAndStrings(String src) {
        char[] out = src.toCharArray();
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                blankNonNewline(out, i, stop);
                i = stop;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                int stop = end < 0 ? n : end;
                blankNonNewline(out, i, stop);
                i = stop;
            } else if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                int end = src.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? n : end + 3;
                blankNonNewline(out, i, stop);
                i = stop;
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out[i] = ' ';
                int k = i + 1;
                while (k < n) {
                    char d = src.charAt(k);
                    if (d == '\\') {
                        out[k] = ' ';
                        if (k + 1 < n) {
                            out[k + 1] = ' ';
                        }
                        k += 2;
                        continue;
                    }
                    if (d == quote) {
                        out[k] = ' ';
                        k++;
                        break;
                    }
                    if (d == '\n') {
                        break;
                    }
                    out[k] = ' ';
                    k++;
                }
                i = k;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static void blankNonNewline(char[] out, int from, int to) {
        for (int k = from; k < to; k++) {
            if (out[k] != '\n' && out[k] != '\r') {
                out[k] = ' ';
            }
        }
    }
}
