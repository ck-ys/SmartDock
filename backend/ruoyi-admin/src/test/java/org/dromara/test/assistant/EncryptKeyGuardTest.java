package org.dromara.test.assistant;

import org.dromara.common.encrypt.core.EncryptKeyResolver;
import org.dromara.web.runner.EncryptKeyMigrationRunner;
import org.dromara.web.runner.EncryptKeyMigrationRunner.ValueKind;
import org.dromara.web.runner.EncryptKeyStartupRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-02 密钥整改纯函数单测：启动自检 + 占位符解析 + 迁移值判定。
 * 不连库、不起 Spring 上下文——迁移的数据库路径由活体验证覆盖。
 */
@Tag("local")
class EncryptKeyGuardTest {

    @Test
    void keyMissingRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> validate(""));
        assertTrue(e.getMessage().contains("SMARTDOCK_AI_CONFIG_KEY"), "报错必须点名缺哪个变量");
        assertTrue(e.getMessage().contains("smartdock.encrypt.ai-config-key"), "报错必须同时给出配置项名，便于对照");
        assertTrue(e.getMessage().contains("不是配置项名"), "报错必须说清环境变量名≠配置项名（2026-11-05 用户 issue 踩过）");
        assertTrue(e.getMessage().contains("完全重启"), "报错必须提醒 setx 后要重启 IDE/终端");
        assertTrue(e.getMessage().contains("openssl rand -base64 24"), "报错必须给出生成方法");
    }

    @Test
    void keyWrongLengthRejected() {
        assertThrows(IllegalStateException.class, () -> validate("0123456789abcde"));          // 15 位
        assertThrows(IllegalStateException.class, () -> validate("0123456789abcdef0"));        // 17 位
        assertThrows(IllegalStateException.class, () -> validate("0123456789abcdef0123456789abcdef0")); // 33 位
    }

    @Test
    void keyNonAsciiRejected() {
        // 16 个字符但含中文：长度校验通过，ASCII 校验必须拦下
        assertThrows(IllegalStateException.class, () -> validate("0123456789abcde中"));
        assertThrows(IllegalStateException.class, () -> validate("0123456789abcde "));
    }

    @Test
    void whitespaceRejected() {
        assertThrows(IllegalStateException.class, () -> validate(" 0123456789abcdef"));
        assertThrows(IllegalStateException.class, () -> validate("0123456789abcdef\n"));
    }

    @Test
    void burnedKeysRejected() {
        assertThrows(IllegalStateException.class, () -> validate("DcsAiCfgEncKey16"));
        assertThrows(IllegalStateException.class, () -> validate("DcsMcpHeadersK16"));
    }

    @Test
    void validLengthsAccepted() {
        assertDoesNotThrow(() -> validate("0123456789abcdef"));
        assertDoesNotThrow(() -> validate("0123456789abcdef01234567"));
        assertDoesNotThrow(() -> validate("0123456789abcdef0123456789abcdef"));
    }

    @Test
    void startupCheckRejectsMissingKeys() {
        MockEnvironment env = new MockEnvironment();
        EncryptKeyStartupRunner runner = new EncryptKeyStartupRunner(env);
        assertThrows(IllegalStateException.class, runner::check);
    }

    @Test
    void startupCheckRejectsDisabledEncryption() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("mybatis-encryptor.enable", "false");
        EncryptKeyStartupRunner runner = new EncryptKeyStartupRunner(env);
        IllegalStateException e = assertThrows(IllegalStateException.class, runner::check);
        assertTrue(e.getMessage().contains("mybatis-encryptor.enable"));
    }

    @Test
    void startupCheckPassesWithTwoDistinctKeys() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("smartdock.encrypt.ai-config-key", "0123456789abcdef0123456789abcdef");
        env.setProperty("smartdock.encrypt.mcp-headers-key", "fedcba9876543210fedcba9876543210");
        assertDoesNotThrow(() -> new EncryptKeyStartupRunner(env).check());
    }

    @Test
    void startupCheckRejectsSameKey() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("smartdock.encrypt.ai-config-key", "0123456789abcdef0123456789abcdef");
        env.setProperty("smartdock.encrypt.mcp-headers-key", "0123456789abcdef0123456789abcdef");
        assertThrows(IllegalStateException.class, new EncryptKeyStartupRunner(env)::check);
    }

    @Test
    void resolverKeepsLegacyLiteralWorking() {
        // 框架原用法（字面量）必须继续可用，避免影响其它使用方
        assertEquals("literal-key-16ch",
            EncryptKeyResolver.resolve(new MockEnvironment(), "literal-key-16ch", ""));
    }

    @Test
    void resolverReadsPlaceholder() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("smartdock.encrypt.mcp-headers-key", "0123456789abcdef0123456789abcdef");
        assertEquals("0123456789abcdef0123456789abcdef",
            EncryptKeyResolver.resolve(env, "${smartdock.encrypt.mcp-headers-key}", ""));
    }

    @Test
    void resolverUnconfiguredPlaceholderFailsLoud() {
        // 带默认值写法：解析成空串 → 必须抛错，不能回退明文
        assertThrows(IllegalStateException.class,
            () -> EncryptKeyResolver.resolve(new MockEnvironment(), "${smartdock.encrypt.mcp-headers-key:}", ""));
        // 不带默认值：Spring 自己抛 IllegalArgumentException
        assertThrows(IllegalArgumentException.class,
            () -> EncryptKeyResolver.resolve(new MockEnvironment(), "${smartdock.encrypt.mcp-headers-key}", ""));
    }

    @Test
    void migrationValueValidation() {
        assertTrue(EncryptKeyMigrationRunner.isValid("{\"Authorization\":\"Bearer x\"}", ValueKind.HEADERS_JSON));
        assertFalse(EncryptKeyMigrationRunner.isValid("not-json", ValueKind.HEADERS_JSON));
        assertFalse(EncryptKeyMigrationRunner.isValid("[1,2]", ValueKind.HEADERS_JSON));
        assertFalse(EncryptKeyMigrationRunner.isValid("", ValueKind.HEADERS_JSON));

        assertTrue(EncryptKeyMigrationRunner.isValid("sk-abcdef123456", ValueKind.API_KEY));
        assertFalse(EncryptKeyMigrationRunner.isValid("line1\nline2", ValueKind.API_KEY));
        assertFalse(EncryptKeyMigrationRunner.isValid("", ValueKind.API_KEY));
    }

    private static void validate(String key) {
        EncryptKeyStartupRunner.validate("SMARTDOCK_AI_CONFIG_KEY", "smartdock.encrypt.ai-config-key", key);
    }
}
