package org.dromara.assistant.core;

import org.dromara.assistant.tools.ToolIdentity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 五需求批 R1 零漂移护栏（2026-10-13）：classpath skills md 与 SkillAssets 内嵌正文
 * 逐字一致（同源生成，改技能两处同步改）；目录按角色裁剪；角色闸与 Holder 语义钉死。
 * 与 PromptAssetsZeroDiffUnitTest 同构（对照方式直读 classpath，不依赖 loadAll 的
 * 外部目录兜底路径——部署机 CWD 差异不会假红）。
 */
class SkillAssetsZeroDiffUnitTest {

    /** 与 SkillAssets 同款 frontmatter 剥离+归一（测试内复刻小段逻辑做对照） */
    private static String classpathBody(String id) throws IOException {
        try (InputStream in = SkillAssets.class.getResourceAsStream("/prompts/skills/" + id + ".md")) {
            if (in == null) {
                return null;
            }
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            s = s.startsWith("﻿") ? s.substring(1) : s;
            if (s.startsWith("---")) {
                int end = s.indexOf("\n---", 3);
                if (end > 0) {
                    int bodyStart = s.indexOf('\n', end + 4);
                    s = bodyStart > 0 ? s.substring(bodyStart + 1) : "";
                }
            }
            return s.replace("\r\n", "\n").strip();
        }
    }

    @Test
    @Tag("dev")
    void classpathSkillBodiesMatchEmbedded() throws IOException {
        for (SkillAssets.Skill s : SkillAssets.SKILLS) {
            String md = classpathBody(s.id());
            assertThat(md).as("classpath prompts/skills/%s.md 必须存在", s.id()).isNotNull();
            assertThat(md).as("技能 %s 内嵌正文与 classpath md 零漂移", s.id())
                .isEqualTo(s.embeddedBody().strip());
        }
        // md 里的 frontmatter id 行与内嵌表一致（防文件名/id 错位复制）
        for (SkillAssets.Skill s : SkillAssets.SKILLS) {
            try (InputStream in = SkillAssets.class.getResourceAsStream("/prompts/skills/" + s.id() + ".md")) {
                String head = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(head).startsWith("---\nid: " + s.id() + "\n");
                // 能力① Q7：触发词 frontmatter 行与内嵌表一致（逗号串 ↔ List 同源零漂移）
                assertThat(head).as("技能 %s triggers 行与内嵌表一致", s.id())
                    .contains("triggers: " + String.join(", ", s.triggers()) + "\n");
            }
        }
    }

    @Test
    @Tag("dev")
    void catalogFilteredByRoleLevel() {
        String student = SkillAssets.catalog(ToolIdentity.LEVEL_MEMBER);
        assertThat(student).contains("data-reading")
            .doesNotContain("create-mission").doesNotContain("urge-wording");
        String publisher = SkillAssets.catalog(ToolIdentity.LEVEL_PUBLISHER);
        assertThat(publisher).contains("data-reading").contains("create-mission").contains("urge-wording");
        // 目录只有一行一技能（id+名称+描述），正文绝不出现在目录里
        assertThat(publisher).doesNotContain("你正在使用「");
    }

    @Test
    @Tag("dev")
    void bodyRoleGateAndUnknownId() {
        assertThat(SkillAssets.body("data-reading", ToolIdentity.LEVEL_MEMBER)).startsWith("你正在使用「数据解读」");
        // 成员档取发布人技能=null（目录侧同口径裁剪，正常不会出现——装饰器/工具兜底）
        assertThat(SkillAssets.body("create-mission", ToolIdentity.LEVEL_MEMBER)).isNull();
        assertThat(SkillAssets.body("no-such-skill", ToolIdentity.LEVEL_ADMIN)).isNull();
        assertThat(SkillAssets.body(null, ToolIdentity.LEVEL_ADMIN)).isNull();
        assertThat(SkillAssets.body(" data-reading ", ToolIdentity.LEVEL_MEMBER)).isNotNull();
        assertThat(SkillAssets.exists("urge-wording")).isTrue();
        assertThat(SkillAssets.exists("bogus")).isFalse();
    }

    @Test
    @Tag("dev")
    void holderClearsOnUnknownOrNullId() {
        AssistantSkillHolder.set("t-zero-diff", "bogus-skill");
        assertThat(AssistantSkillHolder.get("t-zero-diff")).isNull();
        AssistantSkillHolder.set("t-zero-diff", "data-reading");
        assertThat(AssistantSkillHolder.get("t-zero-diff")).isEqualTo("data-reading");
        AssistantSkillHolder.set("t-zero-diff", null);
        assertThat(AssistantSkillHolder.get("t-zero-diff")).isNull();
        AssistantSkillHolder.set("t-zero-diff", "  ");
        assertThat(AssistantSkillHolder.get("t-zero-diff")).isNull();
    }

    @Test
    @Tag("dev")
    void loadedSkillsCoverEmbeddedIds() {
        // 进程缓存加载后 id/名称/描述/minRole 与内嵌表一致（外部目录只覆写正文）
        List<SkillAssets.LoadedSkill> loaded = SkillAssets.skills();
        assertThat(loaded).extracting(SkillAssets.LoadedSkill::id)
            .containsExactlyElementsOf(SkillAssets.SKILLS.stream().map(SkillAssets.Skill::id).toList());
        for (SkillAssets.LoadedSkill ls : loaded) {
            SkillAssets.Skill src = SkillAssets.SKILLS.stream()
                .filter(s -> s.id().equals(ls.id())).findFirst().orElseThrow();
            assertThat(ls.name()).isEqualTo(src.name());
            assertThat(ls.minRole()).isEqualTo(src.minRole());
        }
    }
}
