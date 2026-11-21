package org.dromara.test.arch;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限三段式门禁（批 9 契约席 P1 回修，2026-11-21）：
 * 超管的 {@code *:*:*} 在 sa-token vagueMatch 下匹配不上两段式权限（编译探针实证）——
 * 两段式 = 该端点超管也 403，且 baseline（两段）与 migration（smartdock: 前缀换）两路互相打架。
 * 规矩：业务权限一律 {@code smartdock:X:Y}（≥2 个冒号）；底座自带权限（system:/monitor: 等）
 * 本就三段，无一豁免。扫 @SaCheckPermission 注解与 baseline sys_menu perms 值。
 */
@Tag("local")
class PermSegmentGuardTest {

    private static final Pattern PERM = Pattern.compile("^[a-zA-Z0-9-]+(:[a-zA-Z0-9*!-]+){2,}$");
    private static final Pattern ANNOTATION = Pattern.compile("@SaCheckPermission\\(\"([^\"]+)\"\\)");
    private static final Pattern MENU_PERM = Pattern.compile("INSERT INTO public\\.sys_menu [^)]*?'([a-zA-Z0-9-]+:[a-zA-Z0-9-]+)'[^)]*?\\)");

    @Test
    void 权限串必须三段及以上() throws IOException {
        Path backend = backendRoot();
        List<String> bad = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(backend)) {
            walk.filter(p -> p.toString().endsWith(".java") && !p.toString().contains("target"))
                .forEach(p -> scan(p, ANNOTATION, bad));
        }
        scan(backend.resolve("script/sql/baseline/02_reference.sql"), MENU_PERM, bad);
        assertTrue(bad.isEmpty(),
            () -> "两段式权限（超管 *:*:* 匹配不上=403，且与 migration 前缀换不一致）：" + bad.size() + " 处\n"
                + String.join("\n", bad.stream().limit(20).toList()));
    }

    private void scan(Path file, Pattern pattern, List<String> bad) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String s = Files.readString(file);
            Matcher m = pattern.matcher(s);
            while (m.find()) {
                String p = m.group(1);
                if (!PERM.matcher(p).matches()) {
                    bad.add(file.toString().replace('\\', '/') + " :: " + p);
                }
            }
        } catch (IOException ignored) {
            // 扫不到就跳过，不作为通过依据
        }
    }

    private static Path backendRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isDirectory(p.resolve("ruoyi-modules")) && Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("backend root not found");
    }
}
