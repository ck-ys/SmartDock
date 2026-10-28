package org.dromara.assistant.core;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 批 A 地基零漂移护栏（2026-10-27）：官方格式技能资产（resources/skills/&lt;id&gt;/SKILL.md，
 * YAML frontmatter + 正文）与 SkillAssets 内嵌表逐字段一致——新旧两套资产在装载替换前
 * 必须完全等价，本测试就是那道「同技能集行为零变化」的机器验收。
 *
 * <p>走真官方管线（复制到临时目录后 FileSystemSkillRepository 全链路装载），顺带实测
 * 探针 1 的机制面：目录布局/frontmatter 解析/按名取技能都经官方代码，不经我们复刻。
 * （fat jar 内 classpath 枚举由 E2E 活体兜底，测试类路径为展开目录。）</p>
 */
class SkillOfficialFormatZeroDiffUnitTest {

    private static final Map<String, Integer> ROLE_LEVELS = Map.of(
        "member", 0, "publisher", 1, "admin", 2);

    /** classpath /skills 树复制到临时目录（FileSystemSkillRepository 只吃真实文件目录；
     *  测试类路径为展开目录，toURI 直转 Path 成立） */
    private static Path copySkillsTree(Path target) throws IOException {
        URL root = SkillOfficialFormatZeroDiffUnitTest.class.getResource("/skills");
        assertThat(root).as("classpath /skills 根必须存在（A 地基资产）").isNotNull();
        final Path src;
        try {
            src = Path.of(root.toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException("classpath /skills 无法转文件路径", e);
        }
        try (Stream<Path> files = Files.walk(src)) {
            for (Path p : files.toList()) {
                String rel = src.relativize(p).toString();
                if (rel.isEmpty()) {
                    continue;
                }
                Path dst = target.resolve(rel);
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(p, dst);
                }
            }
        }
        return target;
    }

    private static String skillText(String id) throws IOException {
        try (InputStream in = SkillOfficialFormatZeroDiffUnitTest.class
            .getResourceAsStream("/skills/" + id + "/SKILL.md")) {
            assertThat(in).as("技能 %s 的 SKILL.md 必须存在", id).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @Tag("dev")
    void officialRepoSkillsMatchEmbeddedTable() throws IOException {
        Path base = Files.createTempDirectory("skill-official-zero-diff");
        copySkillsTree(base);
        try (FileSystemSkillRepository repo = new FileSystemSkillRepository(base, false)) {
            List<AgentSkill> loaded = repo.getAllSkills();
            assertThat(loaded).as("官方仓装载技能数").hasSameSizeAs(SkillAssets.SKILLS);
            for (SkillAssets.Skill src : SkillAssets.SKILLS) {
                AgentSkill s = repo.getSkill(src.id());
                assertThat(s).as("官方仓能按名取技能 %s", src.id()).isNotNull();
                assertThat(s.getName()).isEqualTo(src.id());
                assertThat(s.getDescription()).as("技能 %s description", src.id())
                    .isEqualTo(src.description());
                Map<String, Object> meta = s.getMetadata();
                assertThat(meta.get("displayName")).as("技能 %s displayName", src.id())
                    .isEqualTo(src.name());
                assertThat(ROLE_LEVELS.get(meta.get("minRole"))).as("技能 %s minRole 档位", src.id())
                    .isEqualTo(src.minRole());
                assertThat(s.getSkillContent().strip()).as("技能 %s 正文与内嵌表零漂移", src.id())
                    .isEqualTo(src.embeddedBody().strip());
            }
        } finally {
            deleteRecursively(base);
        }
    }

    /** frontmatter 逐行防错位（name 首行/版本号存在），生成侧护栏；
     *  题7 甲反向护栏：触发词块不得回流（d799dd5 声称加了实际没加——三审 dsflash P1-3 抓的假绿灯） */
    @Test
    @Tag("dev")
    void frontmatterShapePinned() throws IOException {
        for (SkillAssets.Skill s : SkillAssets.SKILLS) {
            String md = skillText(s.id());
            assertThat(md).startsWith("---\nname: " + s.id() + "\n");
            assertThat(md).contains("version: ");
            assertThat(md).contains("displayName: " + s.name() + "\n");
            assertThat(md).contains("minRole: ");
            assertThat(md).doesNotContain("triggers:");
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> all = Files.walk(root)) {
            List<Path> list = all.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path p : list) {
                Files.deleteIfExists(p);
            }
        }
    }
}
