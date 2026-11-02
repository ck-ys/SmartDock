package org.dromara.test.assistant;

import org.dromara.assistant.domain.DcsMcpServer;
import org.dromara.common.encrypt.core.EncryptorManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G4-#1 定论性单测：@EncryptField 生效前提 = 类被 EncryptorManager 启动扫描
 * （typeAliasesPackage 逐包 PathMatchingResourcePatternResolver 扫 *.class）收进
 * fieldCache——不在缓存=拦截器静默跳过=明文落库（2026-10-22 实测 g4htest 行明文实锤）。
 * 本测纯 new EncryptorManager(生产同款包表达式) 复现扫描，锁死两个加密实体都在缓存：
 * DcsMcpServer.headers（G4-#1 新增）与 DcsAiConfig.apiKey（仓内在用先例）。
 */
@Tag("local")
class G4EncryptScanTest {

    @Test
    void encryptScanCoversMcpServerHeadersAndAiConfigApiKey() {
        // 与 application.yml mybatis-plus.typeAliasesPackage 逐字一致
        String pkgs = "org.dromara.**.domain,com.smartdock.**.domain";
        EncryptorManager m = new EncryptorManager(pkgs);

        Set<Field> mcp = m.getFieldCache(DcsMcpServer.class);
        assertNotNull(mcp, "DcsMcpServer 未被加密扫描收进 fieldCache（headers 将明文落库）");
        assertTrue(mcp.stream().anyMatch(f -> "headers".equals(f.getName())),
            "DcsMcpServer.headers 应带 @EncryptField（实收字段：" + mcp + "）");

        Set<Field> ai = m.getFieldCache(com.smartdock.domain.DcsAiConfig.class);
        assertNotNull(ai, "DcsAiConfig（先例对照）未被加密扫描收进 fieldCache——扫描机制整体回退");
        assertTrue(ai.stream().anyMatch(f -> "apiKey".equals(f.getName())),
            "DcsAiConfig.apiKey 应带 @EncryptField");
    }

    /** 2026-11-02 密钥整改：注解不再写字面量，只写环境变量占位符；
     *  服务层旧常量 HEADERS_ENC_KEY 必须已删除，防止字面量回潮。 */
    @Test
    void headersKeyComesFromEnvironmentNotLiteral() throws Exception {
        org.dromara.common.encrypt.annotation.EncryptField anno =
            DcsMcpServer.class.getDeclaredField("headers")
                .getAnnotation(org.dromara.common.encrypt.annotation.EncryptField.class);
        assertNotNull(anno, "headers 缺 @EncryptField");
        assertEquals("${smartdock.encrypt.mcp-headers-key}", anno.password(),
            "注解密钥必须是环境变量占位符（禁止字面量）");

        org.springframework.mock.env.MockEnvironment env =
            new org.springframework.mock.env.MockEnvironment();
        env.setProperty("smartdock.encrypt.mcp-headers-key", "0123456789abcdef0123456789abcdef");
        String resolved = org.dromara.common.encrypt.core.EncryptKeyResolver
            .resolve(env, anno.password(), "");
        assertEquals(32, resolved.length(), "解析后应是 32 位真实密钥");

        assertThrows(NoSuchFieldException.class,
            () -> org.dromara.assistant.service.McpPilotService.class.getDeclaredField("HEADERS_ENC_KEY"),
            "旧的字面量常量必须已删除");

        org.dromara.common.encrypt.annotation.EncryptField ai =
            com.smartdock.domain.DcsAiConfig.class.getDeclaredField("apiKey")
                .getAnnotation(org.dromara.common.encrypt.annotation.EncryptField.class);
        assertNotNull(ai, "DcsAiConfig.apiKey 缺 @EncryptField");
        assertEquals("${smartdock.encrypt.ai-config-key}", ai.password(),
            "AI 配置密钥也必须是占位符");
    }

    /** 事故盲区正面试锁（G4 三审回修 dsflash P2）：扫描过≠加解密真的跑得通——
     *  首版 14 位密钥时扫描测试全绿但运行时加密必抛。用注解同款参数（AES+密钥+BASE64）
     *  真实 roundtrip 一遍，算法/密钥/编码任一漂移此处立刻红 */
    @Test
    void headersAesRoundtripWithAnnotationParams() throws Exception {
        org.dromara.common.encrypt.annotation.EncryptField anno =
            DcsMcpServer.class.getDeclaredField("headers")
                .getAnnotation(org.dromara.common.encrypt.annotation.EncryptField.class);
        org.springframework.mock.env.MockEnvironment env =
            new org.springframework.mock.env.MockEnvironment();
        env.setProperty("smartdock.encrypt.mcp-headers-key", "0123456789abcdef0123456789abcdef");
        org.dromara.common.encrypt.core.EncryptContext ctx = new org.dromara.common.encrypt.core.EncryptContext();
        ctx.setAlgorithm(anno.algorithm());
        ctx.setPassword(org.dromara.common.encrypt.core.EncryptKeyResolver
            .resolve(env, anno.password(), ""));
        ctx.setEncode(anno.encode() == org.dromara.common.encrypt.enumd.EncodeType.DEFAULT
            ? org.dromara.common.encrypt.enumd.EncodeType.BASE64 : anno.encode());
        // 传不存在包跳过扫描（encrypt/decrypt 不依赖扫描缓存）
        org.dromara.common.encrypt.core.EncryptorManager m =
            new org.dromara.common.encrypt.core.EncryptorManager("com.no.such.pkg");

        String plain = "{\"Authorization\":\"Bearer rt-test\"}";
        String enc = m.encrypt(plain, ctx);
        assertTrue(enc.startsWith("ENC_"), "密文应带 ENC_ 前缀（幂等判据）");
        assertNotEquals(plain, enc);
        assertEquals(plain, m.decrypt(enc, ctx), "同参数解密应还原明文");
        // 幂等：已带前缀的密文再 encrypt 不二次加密（双轨互认判据）
        assertEquals(enc, m.encrypt(enc, ctx));
    }
}
