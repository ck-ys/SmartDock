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
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写码红线门禁（2026-11-22 批 11 拍板 Q1=1 / Q2=1 / Q3=1 / Q4=1，板：
 * {@code .workshop/plans/20261122-batch11-redline-board.html}）。
 *
 * <p>backend/AGENTS.md「写码红线」共 6 条，其中 <b>5 条</b>从纯文字变成一跑就红的测试；
 * 第 5 条（equals 方向）按 Q3=1 <b>不做门禁</b>——2026-11-22 实测 3 处报告点全部有来源保证
 * （{@code TenantHelper:200} 前有 isNotBlank 守卫 / {@code AiBillingService:283} 来自 substring /
 * {@code SvgXssScanner:68} 来自 toLowerCase），文本扫描分不清「可能为 null」与「必然非空」，
 * 保留为 review 口径：可空变量建议字面量在前。</p>
 *
 * <p>五条规矩，全部零容忍（无白名单、无基线、无行尾豁免标记）：</p>
 * <ol>
 *   <li><b>@Transactional 必须写 rollbackFor，且从 Exception / Throwable 起步</b>：受检异常抛出时默认不回滚，
 *       事务形同虚设；写 {@code RuntimeException.class} 等于没写（那本来就是默认值），{@code = {}} 同理。
 *       裸名与带包名两种写法都认；文件 import 了 jakarta / javax 的事务注解则整条不查
 *       （它用 rollbackOn，没有 rollbackFor 这个属性）。</li>
 *   <li><b>禁 Executors.new* 快捷工厂</b>（例外：{@code newScheduledThreadPool} /
 *       {@code newSingleThreadScheduledExecutor}——调度语义无平替）：静态导入的写法同样算；
 *       JDK 21 的虚拟线程工厂也在禁用之列，要用先改 AGENTS.md 那条规矩。
 *       本条只认「有没有用快捷工厂」，<b>不查</b> {@code ThreadPoolExecutor} 的构造参数——
 *       线程数 / 队列 / 命名工厂 / 拒绝策略写得对不对，只能靠 review。</li>
 *   <li><b>禁 new BigDecimal(浮点字面量)</b>：二进制浮点转十进制必然失真（{@code new BigDecimal(0.1)} ≠ 0.1）。
 *       认带符号 / 带后缀 / 科学计数 / 带包名的字面量；<b>变量传参看不出类型</b>
 *       （{@code new BigDecimal(someDouble)}），只能靠 review。</li>
 *   <li><b>foreach 内不许改被遍历集合</b>：增删会触发 ConcurrentModificationException；正确写法是
 *       {@code removeIf}、迭代器，或先拷贝一份再遍历。只认裸标识符 / {@code this.x} / {@code a.b.x}
 *       做迭代对象，花括号体或单语句体都看。
 *       <b>已知盲区</b>：方法调用做迭代对象追不到接收者。
 *       <b>已知误报面</b>：循环体里的 lambda / 异步任务改集合（其实是稍后才执行）、
 *       循环体内声明了同名局部变量。</li>
 *   <li><b>禁静态 SimpleDateFormat 字段</b>：它非线程安全，静态字段在并发下产出错乱日期。
 *       只认字段声明（类型 + 名字 + {@code =} / {@code ;} / {@code []}），静态方法的返回类型不算；
 *       {@code static final ThreadLocal<SimpleDateFormat>} 是正确写法，不报。
 *       装进 static Map / List 的看不出，只能靠 review。</li>
 * </ol>
 *
 * <p>扫描范围（Q4=1）：{@code backend/**}{@code /src/main/java/**}{@code /*.java}（2026-11-22 实测 1174 个文件）；
 * 测试代码不在扫描面（测试目录里建临时线程池是合法的）。注释与字符串内容不计
 * （复用 {@link PackageHygieneTest#stripCommentsAndStrings}）。</p>
 *
 * <p><b>判据自检</b>（{@link #判据自检_该报的必须报_合法的必须放行()}）：五条规则里有四条在现网零命中——
 * 只扫全仓的话，正则改坏了也一直绿。所以每条规则都钉了一组「该报的 / 合法的」固定样例：
 * 改判据时这里是响亮失败，不是静默失效。</p>
 */
@Tag("local")
class CodeRedlineTest {

    private static final Set<String> SCHEDULED_FACTORIES = Set.of("ScheduledThreadPool", "SingleThreadScheduledExecutor");

    /** 规则 1：裸名或带包名（现网有 3 处写全了包名，只认裸名会漏） */
    private static final Pattern TX_ANNOTATION = Pattern.compile("@((?:[\\w$]+\\.)*Transactional)\\b");
    private static final String SPRING_TX_FQN = "org.springframework.transaction.annotation.Transactional";
    private static final Pattern JAKARTA_TX_IMPORT =
        Pattern.compile("^import\\s+(?:jakarta|javax)\\.transaction\\.Transactional\\s*;", Pattern.MULTILINE);
    /** {@code rollbackFor} 后面必须紧跟 {@code =}（排除 rollbackForClassName），首个元素是 Exception / Throwable */
    private static final Pattern ROLLBACK_FOR_EXCEPTION = Pattern.compile(
        "\\brollbackFor\\s*=\\s*\\{?\\s*(?:[\\w$]+\\.)*(?:Exception|Throwable)\\.class");

    /** 规则 2：调用写法（裸名与带包名都算）+ 静态导入写法 */
    private static final Pattern EXECUTORS_FACTORY_CALL = Pattern.compile("\\bExecutors\\.new(\\w+)\\s*\\(");
    private static final Pattern EXECUTORS_FACTORY_STATIC_IMPORT =
        Pattern.compile("^import\\s+static\\s+java\\.util\\.concurrent\\.Executors\\.new(\\w+)\\s*;", Pattern.MULTILINE);

    /** 规则 3：浮点字面量 = 带小数点，或带指数，或带 f/F/d/D 后缀；整数与 long 字面量不算（那两个构造是精确的） */
    private static final String FLOAT_LITERAL = "[-+]?(?:(?:\\d[\\d_]*\\.[\\d_]*|\\.\\d[\\d_]*)(?:[eE][-+]?[\\d_]+)?[fFdD]?"
        + "|\\d[\\d_]*[eE][-+]?[\\d_]+[fFdD]?"
        + "|\\d[\\d_]*[fFdD])";
    private static final Pattern BIGDECIMAL_DOUBLE = Pattern.compile(
        "new\\s+(?:[\\w$]+\\.)*BigDecimal\\s*\\(\\s*" + FLOAT_LITERAL + "\\s*[,)]");

    /** 规则 4：迭代对象限定为裸标识符链（可带 this. 或别的对象前缀）；循环体可带花括号也可只有一条语句 */
    private static final Pattern FOR_EACH = Pattern.compile(
        "for\\s*\\([^:;]+:\\s*([A-Za-z_$][\\w$.]*)\\s*\\)\\s*(\\{)?");
    private static final String COLLECTION_MUTATORS = "add|addAll|remove|removeAll|retainAll|clear";

    /** 规则 5：字段声明形态——static + 若干修饰符 + 类型（可带包名、可带数组维度）+ 名字 + = / ; / [ */
    private static final Pattern STATIC_SDF_FIELD = Pattern.compile(
        "\\bstatic\\s+(?:(?:final|private|public|protected|transient|volatile)\\s+)*"
            + "(?:[\\w$]+\\.)*SimpleDateFormat(?:\\s*\\[\\s*\\])*\\s+[A-Za-z_$][\\w$]*\\s*(?:=|;|\\[)");

    @Test
    void 事务注解必须显式写rollbackFor() throws IOException {
        List<String> bad = new ArrayList<>();
        for (FileText ft : scan()) {
            for (Hit hit : transactionalHits(ft.code)) {
                bad.add(ft.rel + ":" + ft.line(hit.at()) + " " + hit.what());
            }
        }
        assertThat(bad).as("受检异常抛出时默认不回滚，事务注解的 rollbackFor 必须从 Exception.class 起步").isEmpty();
    }

    @Test
    void 线程池快捷工厂禁用只留两个调度例外() throws IOException {
        List<String> bad = new ArrayList<>();
        for (FileText ft : scan()) {
            for (Hit hit : executorFactoryHits(ft.code)) {
                bad.add(ft.rel + ":" + ft.line(hit.at()) + " " + hit.what());
            }
        }
        assertThat(bad).as("Executors.new* 把线程数/队列/线程工厂藏在默认值里，必须用 ThreadPoolExecutor 显式写出").isEmpty();
    }

    @Test
    void BigDecimal不许直传浮点字面量() throws IOException {
        List<String> bad = new ArrayList<>();
        for (FileText ft : scan()) {
            for (Hit hit : bigDecimalDoubleHits(ft.code)) {
                bad.add(ft.rel + ":" + ft.line(hit.at()) + " " + hit.what());
            }
        }
        assertThat(bad).as("new BigDecimal(0.1) != 0.1：二进制浮点转十进制失真").isEmpty();
    }

    @Test
    void 增强for循环体不许改被遍历集合() throws IOException {
        List<String> bad = new ArrayList<>();
        for (FileText ft : scan()) {
            for (Hit hit : forEachMutationHits(ft.code)) {
                bad.add(ft.rel + ":" + ft.line(hit.at()) + " " + hit.what());
            }
        }
        assertThat(bad).as("foreach 体内增删被遍历集合会触发 ConcurrentModificationException").isEmpty();
    }

    @Test
    void SimpleDateFormat不许做静态字段() throws IOException {
        List<String> bad = new ArrayList<>();
        for (FileText ft : scan()) {
            for (Hit hit : staticSdfFieldHits(ft.code)) {
                bad.add(ft.rel + ":" + ft.line(hit.at()) + " " + hit.what());
            }
        }
        assertThat(bad).as("SimpleDateFormat 非线程安全，静态字段在并发下产出错乱日期").isEmpty();
    }

    /**
     * 门禁自己的测试：每条规则钉一组固定样例（该报的 / 合法的）。样例一律先过同一个去注释器，
     * 顺带钉住「注释与字符串里出现的不算」。
     */
    @Test
    void 判据自检_该报的必须报_合法的必须放行() {
        Function<String, List<Hit>> tx = CodeRedlineTest::transactionalHits;
        assertCases("事务", tx,
            new Sample(1, """
                @Transactional
                public void a() {}
                """),
            new Sample(1, """
                @Transactional(readOnly = true)
                public void a() {}
                """),
            new Sample(1, """
                @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
                public void a() {}
                """),
            // 写 RuntimeException.class 等于没写：Spring 默认本来就回滚 RuntimeException
            new Sample(1, """
                @Transactional(rollbackFor = RuntimeException.class)
                public void a() throws IOException {}
                """),
            new Sample(1, """
                @Transactional(rollbackFor = {})
                public void a() {}
                """),
            new Sample(1, """
                @org.springframework.transaction.annotation.Transactional
                public void a() {}
                """),
            new Sample(0, """
                @Transactional(rollbackFor = Exception.class)
                public void a() {}
                """),
            new Sample(0, """
                @Transactional(
                    rollbackFor = Exception.class,
                    readOnly = true
                )
                public void a() {}
                """),
            new Sample(0, """
                @Transactional(rollbackFor = {Exception.class})
                public void a() {}
                """),
            new Sample(0, """
                @Transactional(rollbackFor = Throwable.class)
                public void a() {}
                """),
            new Sample(0, """
                @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
                public void a() {}
                """),
            // jakarta / javax 的事务注解没有 rollbackFor 属性，不归本条管
            new Sample(0, """
                import jakarta.transaction.Transactional;

                @Transactional
                public class A {}
                """),
            // 注释与字符串里出现的不算；名字相近的别的注解也不算
            new Sample(0, """
                // @Transactional 老写法
                public void a() {}
                String doc = "记得写 @Transactional";
                @TransactionalEventListener
                public void b() {}
                """),
            new Sample(2, """
                @Transactional
                public void a() {}

                @Transactional(readOnly = true)
                public void b() {}
                """));

        Function<String, List<Hit>> pool = CodeRedlineTest::executorFactoryHits;
        assertCases("线程池", pool,
            new Sample(1, "ExecutorService p = Executors.newFixedThreadPool(2);"),
            new Sample(1, "ExecutorService p = Executors.newSingleThreadExecutor();"),
            new Sample(1, "ExecutorService p = java.util.concurrent.Executors.newCachedThreadPool();"),
            new Sample(1, "import static java.util.concurrent.Executors.newCachedThreadPool;"),
            // 虚拟线程工厂同样在禁用之列（要用先改 AGENTS.md 那条规矩）
            new Sample(1, "ExecutorService p = Executors.newVirtualThreadPerTaskExecutor();"),
            new Sample(0, "ScheduledExecutorService s = Executors.newScheduledThreadPool(2);"),
            new Sample(0, "ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor();"),
            new Sample(0, "ThreadFactory f = Executors.defaultThreadFactory();"),
            new Sample(0, """
                // Executors.newFixedThreadPool(4)
                String s = "Executors.newFixedThreadPool(4)";
                """),
            new Sample(0, """
                ExecutorService ok = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), r -> new Thread(r, "named"));
                """));

        Function<String, List<Hit>> decimal = CodeRedlineTest::bigDecimalDoubleHits;
        assertCases("BigDecimal", decimal,
            new Sample(1, "BigDecimal a = new BigDecimal(0.1);"),
            new Sample(1, "BigDecimal a = new BigDecimal(.5);"),
            new Sample(1, "BigDecimal a = new BigDecimal(1e5);"),
            new Sample(1, "BigDecimal a = new BigDecimal(1.5d);"),
            new Sample(1, "BigDecimal a = new BigDecimal(-0.01);"),
            new Sample(1, "BigDecimal a = new BigDecimal(1d);"),
            new Sample(1, "BigDecimal a = new BigDecimal(1.e5);"),
            new Sample(1, "BigDecimal a = new java.math.BigDecimal(0.1);"),
            new Sample(1, "BigDecimal a = new BigDecimal(0.1, java.math.MathContext.DECIMAL128);"),
            new Sample(3, """
                new BigDecimal(0.1);
                new BigDecimal(2.5f);
                new BigDecimal(3D);
                """),
            // 字符串 / 整数 / long 构造是精确的；变量与表达式传参看不出类型，是已知盲区
            new Sample(0, """
                BigDecimal j = new BigDecimal("0.1");
                BigDecimal k = new BigDecimal(123);
                BigDecimal l = new BigDecimal(123L);
                BigDecimal m = BigDecimal.valueOf(0.1);
                BigDecimal n = new BigDecimal(changed);
                BigDecimal o = new BigDecimal(1.0 * price);
                """));

        Function<String, List<Hit>> forEach = CodeRedlineTest::forEachMutationHits;
        assertCases("foreach", forEach,
            new Sample(1, "for (String s : items) { items.remove(s); }"),
            new Sample(1, "for (String s : this.items) { this.items.add(s); }"),
            new Sample(1, "for (String s : items) items.add(s);"),
            new Sample(1, "for (String s : outer.items) { outer.items.clear(); }"),
            new Sample(1, """
                for (String s : items)
                    items.removeAll(singleton(s));
                """),
            // 外层遍历 items、内层遍历 other：只有外层报
            new Sample(1, """
                for (String s : items) {
                    for (String t : other) {
                        items.remove(t);
                    }
                }
                """),
            new Sample(0, "for (String s : items) { items.removeIf(String::isEmpty); other.add(s); }"),
            // 改的是别的对象上同名的集合
            new Sample(0, "for (String s : list) { other.list.add(s); }"),
            // 方法调用做迭代对象：追不到接收者，已知盲区
            new Sample(0, "for (String s : service.list()) { other.add(s); }"),
            // 先拷贝一份再改，是推荐写法
            new Sample(0, "for (String s : new ArrayList<>(items)) { items.remove(s); }"),
            // 只读与排序不算增删
            new Sample(0, "for (String s : items) { items.sort(naturalOrder()); items.get(0); items.size(); }"),
            // 普通 for 循环下标增删不触发 CME
            new Sample(0, "for (int i = 0; i < items.size(); i++) { items.add(i); }"),
            new Sample(0, "for (String s : items) { String text = \"items.add(s)\"; }"),
            // 已知误报面：lambda 里改、其实是稍后才执行。文本扫描分不清「循环里」与「稍后异步里」，
            // 现在会报；哪天判据能分清了，这里必须跟着改成 0
            new Sample(1, "for (String s : items) { tasks.add(() -> items.add(s)); }"));

        Function<String, List<Hit>> sdf = CodeRedlineTest::staticSdfFieldHits;
        assertCases("SimpleDateFormat", sdf,
            new Sample(1, "private static final SimpleDateFormat SDF = new SimpleDateFormat(\"yyyy-MM-dd\");"),
            new Sample(1, "static SimpleDateFormat F;"),
            new Sample(1, "static private final SimpleDateFormat X = null;"),
            new Sample(1, "static final java.text.SimpleDateFormat FMT = null;"),
            new Sample(1, "static final SimpleDateFormat[] POOL = new SimpleDateFormat[4];"),
            new Sample(1, "private static final java.text.SimpleDateFormat[] POOL = new java.text.SimpleDateFormat[4];"),
            // 静态方法的返回类型不是静态字段（返回数组也不是）
            new Sample(0, "public static SimpleDateFormat formatter(String p) { return new SimpleDateFormat(p); }"),
            new Sample(0, "public static SimpleDateFormat[] all(String p) { return new SimpleDateFormat[0]; }"),
            new Sample(0, "private final SimpleDateFormat f = new SimpleDateFormat(\"y\");"),
            new Sample(0, "static final ThreadLocal<SimpleDateFormat> TL = new ThreadLocal<>();"),
            new Sample(0, "void m() { SimpleDateFormat local = new SimpleDateFormat(\"y\"); }"));
    }

    // ------------------------------------------------------------------
    // 五条判据：输入去注释后的代码，输出违规位置与人话
    // ------------------------------------------------------------------

    /** 一处违规：在去注释文本里的起始下标 + 说明 */
    private record Hit(int at, String what) {
    }

    static List<Hit> transactionalHits(String code) {
        List<Hit> hits = new ArrayList<>();
        if (JAKARTA_TX_IMPORT.matcher(code).find()) {
            return hits;
        }
        Matcher m = TX_ANNOTATION.matcher(code);
        while (m.find()) {
            if (!"Transactional".equals(m.group(1)) && !SPRING_TX_FQN.equals(m.group(1))) {
                continue;
            }
            int i = skipWhitespace(code, m.end());
            if (i >= code.length() || code.charAt(i) != '(') {
                hits.add(new Hit(m.start(), "@Transactional 未带任何参数（缺 rollbackFor）"));
                continue;
            }
            int close = matching(code, i, '(', ')');
            if (!ROLLBACK_FOR_EXCEPTION.matcher(code.substring(i, close + 1)).find()) {
                hits.add(new Hit(m.start(), "@Transactional(...) 的 rollbackFor 没有从 Exception.class / Throwable.class 起步"));
            }
        }
        return hits;
    }

    static List<Hit> executorFactoryHits(String code) {
        List<Hit> hits = new ArrayList<>();
        Matcher call = EXECUTORS_FACTORY_CALL.matcher(code);
        while (call.find()) {
            if (!SCHEDULED_FACTORIES.contains(call.group(1))) {
                hits.add(new Hit(call.start(), "Executors.new" + call.group(1)
                    + "(...) —— 用 ThreadPoolExecutor 把线程数 / 队列 / 线程工厂显式写出来（调度场景除外）"));
            }
        }
        Matcher staticImport = EXECUTORS_FACTORY_STATIC_IMPORT.matcher(code);
        while (staticImport.find()) {
            if (!SCHEDULED_FACTORIES.contains(staticImport.group(1))) {
                hits.add(new Hit(staticImport.start(), "静态导入 Executors.new" + staticImport.group(1)
                    + " —— 换个写法绕不过去，同样要用 ThreadPoolExecutor"));
            }
        }
        return hits;
    }

    static List<Hit> bigDecimalDoubleHits(String code) {
        List<Hit> hits = new ArrayList<>();
        Matcher m = BIGDECIMAL_DOUBLE.matcher(code);
        while (m.find()) {
            hits.add(new Hit(m.start(), "new BigDecimal(浮点字面量) 必然失真 —— 换字符串构造或 BigDecimal.valueOf"));
        }
        return hits;
    }

    static List<Hit> forEachMutationHits(String code) {
        List<Hit> hits = new ArrayList<>();
        Matcher m = FOR_EACH.matcher(code);
        while (m.find()) {
            String iterable = m.group(1);
            int dot = iterable.lastIndexOf('.');
            String base = dot < 0 ? iterable : iterable.substring(dot + 1);
            String body;
            if (m.group(2) != null) {
                int open = m.end() - 1;
                body = code.substring(open, matching(code, open, '{', '}') + 1);
            } else {
                int end = code.indexOf(';', m.end());
                body = code.substring(m.end(), end < 0 ? code.length() : end);
            }
            // 裸 base 前面不能紧跟字母/数字/./$（排除 other.list.add）；带前缀时只认同一个前缀
            String receiver = "(?<![\\w$.])" + Pattern.quote(base)
                + (dot < 0 ? "" : "|" + Pattern.quote(iterable));
            Matcher mod = Pattern.compile("(?:" + receiver + ")\\.(?:" + COLLECTION_MUTATORS + ")\\s*\\(").matcher(body);
            if (mod.find()) {
                hits.add(new Hit(m.start(), "循环头遍历 " + iterable + "，体内又对它增删 —— 换 removeIf、迭代器或先拷贝一份"));
            }
        }
        return hits;
    }

    static List<Hit> staticSdfFieldHits(String code) {
        List<Hit> hits = new ArrayList<>();
        Matcher m = STATIC_SDF_FIELD.matcher(code);
        while (m.find()) {
            hits.add(new Hit(m.start(), "静态 SimpleDateFormat 字段非线程安全 —— 换 DateTimeFormatter（不可变）、ThreadLocal 或方法内新建"));
        }
        return hits;
    }

    // ------------------------------------------------------------------
    // 判据自检的工具
    // ------------------------------------------------------------------

    /** 一段样例代码 + 期望报几处（0 = 合法写法，必须放行） */
    private record Sample(int expect, String code) {
        Sample(int expect, String... lines) {
            this(expect, String.join("\n", lines));
        }

        @Override
        public String toString() {
            return code;
        }
    }

    private static void assertCases(String rule, Function<String, List<Hit>> detector, Sample... samples) {
        for (Sample sample : samples) {
            List<Hit> actual = detector.apply(PackageHygieneTest.stripCommentsAndStrings(sample.code()));
            assertThat(actual).as(rule + " 判据：下面这段应报 " + sample.expect() + " 处，实际报了 " + actual + "\n" + sample)
                .hasSize(sample.expect());
        }
    }

    // ------------------------------------------------------------------
    // 文件与文本
    // ------------------------------------------------------------------

    private record FileText(String rel, String code) {
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
            String rel = backend.relativize(file).toString().replace('\\', '/');
            String raw;
            try {
                raw = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // 不带路径的话，surefire 里只会看到一个 ERROR，分不清是哪份源码读不了
                throw new IOException("CodeRedlineTest 读不了 " + rel + "（门禁要求源码是 UTF-8）", e);
            }
            out.add(new FileText(rel, PackageHygieneTest.stripCommentsAndStrings(raw)));
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

    private static int skipWhitespace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
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
}
