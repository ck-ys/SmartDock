package org.dromara.test.assistant;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批 10（2026-11-22）多审契约席 P1-2 的回归钉子：dev profile 不得用字面量覆盖占位符。
 *
 * <p><b>病灶</b>：{@code application-dev.yml} 的 {@code rollout.content-moderation-enabled}
 * 与 {@code content-block-enabled} 原先写死 {@code true} / {@code false}。而 dev profile 在
 * {@code backend/pom.xml} 里是 {@code activeByDefault=true}，交付 jar 的
 * {@code application.yml} 就是 {@code active: dev}——启动时 profile 专属源优先级更高，
 * 会把主文件里的 {@code ${SMARTDOCK_CONTENT_*:...}} 占位符整个盖掉，那个占位符根本不被求值。
 * 运维设 {@code SMARTDOCK_CONTENT_MODERATION_ENABLED=false} 时日志无报错、行为不变（静默失效）。</p>
 *
 * <p><b>为什么读 target/classes 而不是 src</b>：{@code src} 下的 {@code application.yml} 含
 * 3 处 maven filter 占位符（{@code @logging.level@} 等），{@code YamlPropertySourceLoader}
 * 会报「found character '@' that cannot start any token」。只有 maven 过滤后的产物可加载——
 * 而产物正是运维拿到的那份，测它才有意义。</p>
 *
 * <p><b>可测性</b>：纯文件解析 + {@link StandardEnvironment} 占位符解析，不起 Spring 上下文、
 * 不连库，故打 {@code @Tag("local")}。</p>
 */
@Tag("local")
class DevProfilePlaceholderTest {

    /** 交付产物目录（maven 过滤后）。测试从 ruoyi-admin 模块跑，工作目录是该模块根。 */
    private static final String CLASSES = "target/classes/";

    private static PropertySource<?> load(String file) throws Exception {
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load(file, new FileSystemResource(CLASSES + file));
        assertFalse(sources.isEmpty(), file + " 必须能加载出至少一个 PropertySource");
        return sources.get(0);
    }

    /**
     * 按 key 在所有 YAML 文档段里找（application-dev.yml 用 {@code ---} 分成多段：
     * 第一段是 captcha，rollout 在后面几段）。找不到返回 null。
     */
    private static Object find(String file, String key) throws Exception {
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load(file, new FileSystemResource(CLASSES + file));
        assertFalse(sources.isEmpty(), file + " 必须能加载出至少一个 PropertySource");
        for (PropertySource<?> ps : sources) {
            Object v = ps.getProperty(key);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    @Test
    void devProfile不得把内容机审开关写死成字面量() throws Exception {
        Object moderation = find("application-dev.yml", "rollout.content-moderation-enabled");
        Object block = find("application-dev.yml", "rollout.content-block-enabled");

        // 关键断言：必须是占位符形态，不能是裸的 true/false 字面量
        assertTrue(String.valueOf(moderation).contains("SMARTDOCK_CONTENT_MODERATION_ENABLED"),
            "dev profile 的 content-moderation-enabled 必须是占位符（否则交付产物里环境变量静默失效），实际值：" + moderation);
        assertTrue(String.valueOf(block).contains("SMARTDOCK_CONTENT_BLOCK_ENABLED"),
            "dev profile 的 content-block-enabled 必须是占位符，实际值：" + block);
    }

    @Test
    void devProfile占位符三种场景都按双读口径解析() throws Exception {
        String rawModeration = String.valueOf(find("application-dev.yml", "rollout.content-moderation-enabled"));

        // 场景 1：都没设 → 落到 dev 的原默认值 true（保持 dev 观察模式行为不变）
        assertEquals("true", resolve(rawModeration, null, null),
            "都不设时必须回落到 dev 原默认值 true（不能悄悄变成 false，那会改变 dev 行为）");

        // 场景 2：只设旧名 → 用旧名
        assertEquals("false", resolve(rawModeration, null, "false"),
            "只设旧名 DCS_* 时必须生效（老部署不用改）");

        // 场景 3：只设新名 → 用新名
        assertEquals("false", resolve(rawModeration, "false", null),
            "只设新名 SMARTDOCK_* 时必须生效");

        // 场景 4：两个都设且冲突 → 新名优先
        assertEquals("false", resolve(rawModeration, "false", "true"),
            "两个都设时必须新名优先（这是双读过渡的核心口径）");
    }

    /** 用 Spring 自己的占位符解析器求值，不自己写解析逻辑（否则测的是我的复刻而不是真实行为） */
    private static String resolve(String template, String newName, String oldName) {
        StandardEnvironment env = new StandardEnvironment();
        if (newName != null) {
            System.setProperty("SMARTDOCK_CONTENT_MODERATION_ENABLED", newName);
        }
        if (oldName != null) {
            System.setProperty("DCS_CONTENT_MODERATION_ENABLED", oldName);
        }
        try {
            return env.resolvePlaceholders(template);
        } finally {
            System.clearProperty("SMARTDOCK_CONTENT_MODERATION_ENABLED");
            System.clearProperty("DCS_CONTENT_MODERATION_ENABLED");
        }
    }
}
