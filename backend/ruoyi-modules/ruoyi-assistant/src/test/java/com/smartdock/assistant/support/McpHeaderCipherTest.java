package com.smartdock.assistant.support;

import com.smartdock.assistant.domain.model.McpServer;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.encrypt.core.EncryptorManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 加密件搬进 support 后的行为钉（批 5 题 3 案 1，2026-11-19）。
 *
 * <p>McpHeaderCipher 是「明文绝不落库」的唯一实现点：保存面加密、回显只出键名、
 * 挂载前解密。拆类时最容易出的错是「解密分支悄悄退化成原样返回」（= 把密文当请求头发出去）
 * 或「密钥读错属性名」，这里把三条路径和两条 fail-loud 全钉死。</p>
 *
 * <p>纯内存：EncryptorManager 用空包表达式（不收任何实体，只借 AES 加解密能力），
 * 密钥用 MockEnvironment 注入，不读环境变量、不连库。</p>
 */
@Tag("local")
class McpHeaderCipherTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private static McpHeaderCipher cipherWithManager(String key) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("encryptorManager", new EncryptorManager("com.no.such.pkg"));
        MockEnvironment env = new MockEnvironment();
        if (key != null) {
            env.setProperty("smartdock.encrypt.mcp-headers-key", key);
        }
        return new McpHeaderCipher(bf.getBeanProvider(EncryptorManager.class), env);
    }

    private static McpHeaderCipher cipherWithoutManager(String key) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        MockEnvironment env = new MockEnvironment();
        if (key != null) {
            env.setProperty("smartdock.encrypt.mcp-headers-key", key);
        }
        return new McpHeaderCipher(bf.getBeanProvider(EncryptorManager.class), env);
    }

    @Test
    void nothingToEncryptPassesThrough() {
        McpHeaderCipher c = cipherWithManager(KEY);
        assertThat(c.encrypt(null)).isNull();
        assertThat(c.encrypt("")).isEmpty();
        assertThat(c.encrypt("   ")).isEqualTo("   ");
        assertThat(c.encrypt("{}")).isEqualTo("{}");
        // ENC_ 幂等：重复保存不得二次加密
        assertThat(c.encrypt("ENC_already")).isEqualTo("ENC_already");
    }

    @Test
    void encryptThenDecryptRoundtripAndIdempotent() {
        McpHeaderCipher c = cipherWithManager(KEY);
        String plain = "{\"X-A\":\"v1\",\"Authorization\":\"Bearer t\"}";

        String enc = c.encrypt(plain);
        assertThat(enc).startsWith("ENC_");
        assertThat(enc).isNotEqualTo(plain);
        assertThat(c.encrypt(enc)).as("再加密应幂等直通").isEqualTo(enc);
        assertThat(c.decrypt(enc)).isEqualTo(plain);
        // 历史明文行原样返回（老数据兼容）
        assertThat(c.decrypt(plain)).isEqualTo(plain);
        assertThat(c.decrypt(null)).isNull();
    }

    @Test
    void headerKeysOfReturnsKeyNamesOnlyNeverValues() {
        McpHeaderCipher c = cipherWithManager(KEY);
        McpServer row = new McpServer();
        row.setServerName("demo");
        row.setHeaders(c.encrypt("{\"X-A\":\"secret-a\",\"Authorization\":\"Bearer topsecret\"}"));

        String keys = c.headerKeysOf(row);
        assertThat(keys).isEqualTo("X-A,Authorization");
        assertThat(keys).doesNotContain("secret");
    }

    @Test
    void headerKeysOfDegradesInsteadOfBlowingUpTheList() {
        McpHeaderCipher c = cipherWithManager(KEY);
        assertThat(c.headerKeysOf(null)).as("null 行不得 NPE").isEmpty();

        McpServer empty = new McpServer();
        empty.setHeaders("{}");
        assertThat(c.headerKeysOf(empty)).isEmpty();

        McpServer bad = new McpServer();
        bad.setServerName("bad_row");
        bad.setHeaders("not-json");
        assertThat(c.headerKeysOf(bad)).as("单行坏数据只降级该行").isEqualTo("(解析失败)");
    }

    @Test
    void failLoudWhenEncryptorMissing() {
        McpHeaderCipher c = cipherWithoutManager(KEY);
        assertThatThrownBy(() -> c.encrypt("{\"X-A\":\"v\"}"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("加密组件未启用");
        // 解密分支不许原样返回密文（否则挂载会把 ENC_... 当请求头发出去）
        assertThatThrownBy(() -> c.decrypt("ENC_xxx"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("无法读取 MCP 请求头");
        // 列表接口：解密失败降级，不 500 整个清单
        McpServer row = new McpServer();
        row.setHeaders("ENC_xxx");
        assertThat(c.headerKeysOf(row)).isEqualTo("(解析失败)");
    }

    @Test
    void failLoudWhenKeyMissing() {
        McpHeaderCipher c = cipherWithManager(null);
        assertThatThrownBy(() -> c.encrypt("{\"X-A\":\"v\"}"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("SMARTDOCK_MCP_HEADERS_KEY");
    }
}
