package org.dromara.test.arch;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分层门禁（2026-11-16 拍板 Q2-1 / Q3-1，2026-11-17 落地）：用 ArchUnit 读字节码，
 * 挡住「Controller 又直接摸数据库 / 又去借别的模块的 Service」的回潮。
 *
 * <p>范围只框助手模块的两个 Controller 包，不碰底座（{@code org.dromara.system} 等），
 * 避免规则过严误伤 RuoYi 自带代码。存量违规在 {@code arch-baseline.json} 里冻结，
 * <b>只减不增</b>；修掉一条就删一行。</p>
 *
 * <p>刷新清单：{@code ARCH_BASELINE_UPDATE=1 mvn -pl ruoyi-admin -P local test -Dtest=LayeringTest}。</p>
 */
@Tag("local")
class LayeringTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DefaultPrettyPrinter PRETTY = new DefaultPrettyPrinter()
        .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
    private static final boolean UPDATE = "1".equals(System.getenv("ARCH_BASELINE_UPDATE"));

    @Test
    void Controller不许直接摸Mapper() throws IOException {
        Map<String, Set<String>> actual = dependencies(
            LayeringTest::isMapper,
            "org.dromara.assistant.controller", "com.smartdock.assistant.controller");
        assertNoNew("controllerMapper", actual, "Controller 直接依赖 Mapper（把查询下沉到 Service）");
    }

    @Test
    void Controller不许借别的模块的Service() throws IOException {
        Map<String, Set<String>> actual = dependencies(
            LayeringTest::isForeignService,
            "org.dromara.assistant.controller", "com.smartdock.assistant.controller");
        assertNoNew("controllerForeignService", actual, "Controller 依赖助手模块以外的 Service（走本模块 Service 或适配器）");
    }

    // ------------------------------------------------------------------

    /**
     * 收集指定包内 Controller 对目标类型的直接依赖。
     *
     * <p>「直接依赖」= 字段类型 / 方法返回值 / 参数类型 / 构造器参数 / 字节码里的字段访问与方法调用。
     * 不做传递分析：A 依赖 Service、Service 依赖 Mapper 是正常分层，不算 Controller 摸库。</p>
     */
    private static Map<String, Set<String>> dependencies(Predicate<JavaClass> target,
                                                         String... packages) {
        JavaClasses classes = new ClassFileImporter().importPackages(packages);
        Map<String, Set<String>> result = new TreeMap<>();
        for (JavaClass c : classes) {
            if (!isController(c)) {
                continue;
            }
            Set<String> deps = new TreeSet<>();
            for (JavaField f : c.getFields()) {
                add(deps, f.getRawType(), target);
            }
            for (var method : c.getMethods()) {
                add(deps, method.getRawReturnType(), target);
                method.getRawParameterTypes().forEach(p -> add(deps, p, target));
                addAccesses(deps, method, target);
            }
            for (var ctor : c.getConstructors()) {
                ctor.getRawParameterTypes().forEach(p -> add(deps, p, target));
                addAccesses(deps, ctor, target);
            }
            if (!deps.isEmpty()) {
                result.put(c.getName(), deps);
            }
        }
        return result;
    }

    private static void addAccesses(Set<String> deps, JavaCodeUnit codeUnit, Predicate<JavaClass> target) {
        for (JavaAccess<?> access : codeUnit.getAccessesFromSelf()) {
            AccessTarget to = access.getTarget();
            add(deps, to.getOwner(), target);
        }
    }

    private static void add(Set<String> deps, JavaClass type, Predicate<JavaClass> target) {
        if (type != null && target.test(type)) {
            deps.add(type.getName());
        }
    }

    private static boolean isController(JavaClass c) {
        String pkg = c.getPackageName();
        return c.getSimpleName().endsWith("Controller")
            && ("org.dromara.assistant.controller".equals(pkg) || "com.smartdock.assistant.controller".equals(pkg));
    }

    private static boolean isMapper(JavaClass c) {
        return c.getSimpleName().endsWith("Mapper");
    }

    /** 别的模块的 Service：名字以 Service 结尾，且不在助手模块（org/com 两个根）里 */
    private static boolean isForeignService(JavaClass c) {
        if (!c.getSimpleName().endsWith("Service")) {
            return false;
        }
        String pkg = c.getPackageName();
        return !(pkg.startsWith("org.dromara.assistant") || pkg.startsWith("com.smartdock.assistant"));
    }

    // ------------------------------------------------------------------
    // 基线：只减不增
    // ------------------------------------------------------------------

    private static void assertNoNew(String section, Map<String, Set<String>> actual, String what) throws IOException {
        Path backend = backendRoot();
        ObjectNode baseline = readBaseline(backend);
        List<String> flat = new ArrayList<>();
        actual.forEach((controller, deps) -> deps.forEach(dep -> flat.add(controller + " -> " + dep)));
        flat.sort(String::compareTo);

        if (UPDATE) {
            ArrayNode arr = baseline.putArray(section);
            flat.forEach(arr::add);
            Files.createDirectories(baselineFile(backend).getParent());
            Files.writeString(baselineFile(backend),
                JSON.writer(PRETTY).writeValueAsString(baseline) + "\n", StandardCharsets.UTF_8);
            System.out.println("[arch-baseline] " + section + " = " + flat.size() + " 条");
            return;
        }

        assertThat(baseline.has(section))
            .as("arch-baseline.json 缺 %s 段；用 ARCH_BASELINE_UPDATE=1 生成", section)
            .isTrue();
        Set<String> frozen = new LinkedHashSet<>();
        for (JsonNode n : baseline.get(section)) {
            frozen.add(n.asText());
        }

        List<String> added = new ArrayList<>();
        for (String key : flat) {
            if (!frozen.contains(key)) {
                added.add(key);
            }
        }
        List<String> stale = new ArrayList<>();
        Set<String> now = new LinkedHashSet<>(flat);
        for (String key : frozen) {
            if (!now.contains(key)) {
                stale.add(key);
            }
        }

        assertThat(added).as("%s：发现新依赖（只减不增）", what).isEmpty();
        assertThat(stale).as("%s：基线里的条目已经改好了——请删掉 arch-baseline.json 里对应行", what).isEmpty();
    }

    private static Path backendRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null) {
            if (Files.isDirectory(p.resolve("ruoyi-modules")) && Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("找不到 backend 根目录");
    }

    private static Path baselineFile(Path backend) {
        return backend.resolve("ruoyi-admin/src/test/resources/arch-baseline.json");
    }

    private static ObjectNode readBaseline(Path backend) throws IOException {
        Path file = baselineFile(backend);
        if (!Files.isRegularFile(file)) {
            return JSON.createObjectNode();
        }
        JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
        return node.isObject() ? (ObjectNode) node : JSON.createObjectNode();
    }
}
