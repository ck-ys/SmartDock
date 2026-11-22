package org.dromara.test.arch;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写码红线门禁（2026-11-22 批 11 拍板 Q1=1 / Q2=1 / Q3=1 / Q4=1，板：
 * {@code .workshop/plans/20261122-batch11-redline-board.html}）。
 *
 * <p>backend/AGENTS.md「写码红线」共 6 条，本批起其中 <b>5 条</b>从纯文字变成一跑就红的测试；
 * 第 6 条（equals 方向）按 Q3=1 拍板<b>不做门禁</b>——2026-11-22 实测 3 处报告点全部是有判空守卫的
 * 可接受形态（误报率 100%），文本扫描分不清「变量可能为 null」与「变量必然非空」，
 * 保留为 review 口径：可空变量建议字面量在前。</p>
 *
 * <p>五条规矩，全部零容忍（无白名单、无基线）：</p>
 * <ol>
 *   <li><b>@Transactional 必须写 rollbackFor</b>：受检异常抛出时默认不回滚，事务形同虚设。</li>
 *   <li><b>禁 Executors.new* 快捷工厂</b>（例外：{@code newScheduledThreadPool} /
 *       {@code newSingleThreadScheduledExecutor}——调度语义无平替）：线程数/队列/拒绝策略必须显式。
 *       批 11 起 {@code AssistantAttachmentService.PARSE_EXECUTOR} 已等价改写为
 *       {@code ThreadPoolExecutor(1,1,0L,MILLISECONDS,LinkedBlockingQueue,工厂)}（Q1=1），
 *       故本条零容忍、无白名单。</li>
 *   <li><b>禁 new BigDecimal(double)</b>：二进制浮点转十进制必然失真（{@code new BigDecimal(0.1)}
 *       ≠ 0.1）。文本扫描只能拦「数字字面量直传」形态，变量传参请在 review 时盯。</li>
 *   <li><b>foreach 内不许改被遍历集合</b>：add/remove/clear 在增强 for 循环体里触发
 *       ConcurrentModificationException；正确写法是 {@code removeIf} 或迭代器。
 *       只匹配「裸标识符集合」形态，方法调用返回值当迭代对象的场景文本扫描追不到。</li>
 *   <li><b>禁静态 SimpleDateFormat</b>：它非线程安全，静态字段在并发下产出错乱日期；
 *       要么 DateTimeFormatter（不可变），要么方法内新建。</li>
 * </ol>
 *
 * <p>扫描范围（Q4=1）：{@code backend/**}{@code /src/main/java/**}{@code /*.java}（生产代码 1174 文件）；
 * 测试代码不在扫描面（2026-11-22 实测测试里 17 处 Executors 用法全是合法的临时线程池）。
 * 注释与字符串内容不计（复用 {@link PackageHygieneTest#stripCommentsAndStrings}）。</p>
 */
@Tag("local")
class CodeRedlineTest {

    /** 规则 2 的例外清单：调度语义（周期/延迟执行）没有 ThreadPoolExecutor 平替，AGENTS.md 明文豁免 */
    private static final Pattern EXECUTORS_FACTORY = Pattern.compile(
        "\\bExecutors\\.new(\\w+)\\s*\\(");

    private static final Pattern BIGDECIMAL_DOUBLE = Pattern.compile(
        "new\\s+BigDecimal\\s*\\(\\s*(?:\\d[\\d_]*\\.[\\d_]*|\\.\\d[\\d_]*|\\d[\\d_]*[eE][-+]?\\d+)[fFdD]?\\s*[,)]");

    /** 增强循环头：迭代对象限定为裸标识符（可带 this. 前缀）——方法调用返回值追不到接收者 */
    private static final Pattern FOR_EACH = Pattern.compile(
        "for\\s*\\([^:;]+:\\s*([A-Za-z_$][\\w$.]*)\\s*\\)\\s*\\{");

    private static final Pattern STATIC_SDF = Pattern.compile(
        "\\bstatic\\s+(?:final\\s+)?SimpleDateFormat\\b");

    @Test
    void 事务注解必须显式写rollbackFor() throws IOException {
        List<String> bad = new ArrayList<>();
        for (FileText ft : scan()) {
            Matcher m = Pattern.compile("@Transactional\\b").matcher(ft.code);
            while (m.find()) {
                int i = m.end();
                while (i < ft.code.length() && Character.isWhitespace(ft.code.charAt(i))) {
                    i++;
                }
                if (i >= ft.code.length() || ft.code.charAt(i) != '(') {
                    bad.add(ft.rel + ":" + ft.line(m.start()) + " @Transactional 未带任何参数（缺 rollbackFor）");
                    continue;
                }
                int close = matchingParen(ft.code, i);
                if (!ft.code.substring(i, close).contains("rollbackFor")) {
                    bad.add(ft.rel + ":" + ft.line(m.start()) + " @Transactional(...) 里没有 rollbackFor");
                }
            }
        }
        assertThat(bad).as("受检异常抛出时默认不回滚，事务注解必须 rollbackFor = Exception.class 起步").isEmpty();
    }

    @Test
    void 线程池快捷工厂禁用只留两个调度例外() throws IOException {
        List<String> bad = new ArrayList<>();
        for (FileText ft : scan()) {
            Matcher m = EXECUTORS_FACTORY.matcher(ft.code);
            while (m.find()) {
                String factory = m.group(1);
                if ("ScheduledThreadPool".equals(factory) || "SingleThreadScheduledExecutor".equals(factory)) {
                    continue;
                }
                bad.add(ft.rel + ":" + ft.line(m.start()) + " Executors.new" + factory
                    + "(...) —— 用 ThreadPoolExecutor 显式写出线程数/队列/拒绝策略（调度场景除外）");
            }
        }
        assertThat(bad).as("Executors.new* 把核心线程数/队列容量/拒绝策略藏在默认值里，必须显式").isEmpty();
    }

    @Test
    void BigDecimal不许直传浮点字面量() throws IOException {
        List<String> bad = new ArrayList<>();
        for (FileText ft : scan()) {
            Matcher m = BIGDECIMAL_DOUBLE.matcher(ft.code);
            while (m.find()) {
                bad.add(ft.rel + ":" + ft.line(m.start())
                    + " new BigDecimal(浮点字面量) 必然失真 —— 换字符串构造或 BigDecimal.valueOf");
            }
        }
        assertThat(bad).as("new BigDecimal(0.1) != 0.1：二进制浮点转十进制失真").isEmpty();
    }

    @Test
    void 增强for循环体不许改被遍历集合() throws IOException {
        List<String> bad = new ArrayList<>();
        for (FileText ft : scan()) {
            Matcher m = FOR_EACH.matcher(ft.code);
            while (m.find()) {
                String base = lastSegment(m.group(1));
                int open = ft.code.indexOf('{', m.end() - 1);
                int close = matchingBrace(ft.code, open);
                String body = ft.code.substring(open, close);
                Matcher mod = Pattern.compile("\\b" + Pattern.quote(base)
                    + "\\.(?:add|addAll|remove|removeAll|clear)\\s*\\(").matcher(body);
                if (mod.find()) {
                    bad.add(ft.rel + ":" + ft.line(m.start()) + " 循环头遍历 " + base
                        + "，体内又对它 add/remove/clear —— 换 removeIf 或迭代器");
                }
            }
        }
        assertThat(bad).as("foreach 体内改被遍历集合会触发 ConcurrentModificationException").isEmpty();
    }

    @Test
    void SimpleDateFormat不许做静态字段() throws IOException {
        List<String> bad = new ArrayList<>();
        for (FileText ft : scan()) {
            Matcher m = STATIC_SDF.matcher(ft.code);
            while (m.find()) {
                bad.add(ft.rel + ":" + ft.line(m.start())
                    + " 静态 SimpleDateFormat 非线程安全 —— 换 DateTimeFormatter（不可变）或方法内新建");
            }
        }
        assertThat(bad).as("SimpleDateFormat 非线程安全，静态字段在并发下产出错乱日期").isEmpty();
    }

    // ------------------------------------------------------------------
    // 扫描与工具
    // ------------------------------------------------------------------

    private record FileText(String rel, String raw, String code) {
        int line(int idx) {
            int n = 1;
            for (int i = 0; i < idx && i < code.length(); i++) {
                if (code.charAt(i) == '\n') {
                    n++;
                }
            }
            return n;
        }
    }

    private static List<FileText> scan() throws IOException {
        Path backend = backendRoot();
        List<FileText> out = new ArrayList<>();
        for (Path file : javaFiles(backend)) {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            out.add(new FileText(backend.relativize(file).toString().replace('\\', '/'),
                raw, PackageHygieneTest.stripCommentsAndStrings(raw)));
        }
        return out;
    }

    /** 与 PackageHygieneTest 同款：从当前目录向上找 backend 根（ruoyi-modules + pom.xml） */
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

    private static int matchingParen(String s, int open) {
        return matching(s, open, '(', ')');
    }

    private static int matchingBrace(String s, int open) {
        return matching(s, open, '{', '}');
    }

    private static int matching(String s, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char d = s.charAt(i);
            if (d == o) {
                depth++;
            } else if (d == c && --depth == 0) {
                return i;
            }
        }
        return s.length();
    }

    private static String lastSegment(String dotted) {
        int dot = dotted.lastIndexOf('.');
        return dot < 0 ? dotted : dotted.substring(dot + 1);
    }
}
