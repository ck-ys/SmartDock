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

    /** G4-#1 事故复现锁：首版密钥 14 位 → 拦截器吞掉 IllegalArgument 后明文落库。
     *  AES 硬性要求 16/24/32 位；注解与显式路径两个密钥必须同值（ENC_ 前缀幂等互认） */
    @Test
    void headersAesKeyLengthValidAndDualTrackAligned() throws Exception {
        org.dromara.common.encrypt.annotation.EncryptField anno =
            DcsMcpServer.class.getDeclaredField("headers")
                .getAnnotation(org.dromara.common.encrypt.annotation.EncryptField.class);
        assertNotNull(anno, "headers 缺 @EncryptField");
        int len = anno.password().length();
        assertTrue(len == 16 || len == 24 || len == 32,
            "AES 密钥长度必须 16/24/32 位（当前 " + len + " 位——首版事故根因，严禁回退）");

        java.lang.reflect.Field constField = org.dromara.assistant.service.McpPilotService.class
            .getDeclaredField("HEADERS_ENC_KEY");
        constField.setAccessible(true);
        assertEquals(anno.password(), constField.get(null),
            "实体注解与服务层显式加密必须同密钥（否则双轨互解不开）");
    }

    /** 事故盲区正面试锁（G4 三审回修 dsflash P2）：扫描过≠加解密真的跑得通——
     *  首版 14 位密钥时扫描测试全绿但运行时加密必抛。用注解同款参数（AES+密钥+BASE64）
     *  真实 roundtrip 一遍，算法/密钥/编码任一漂移此处立刻红 */
    @Test
    void headersAesRoundtripWithAnnotationParams() throws Exception {
        org.dromara.common.encrypt.annotation.EncryptField anno =
            DcsMcpServer.class.getDeclaredField("headers")
                .getAnnotation(org.dromara.common.encrypt.annotation.EncryptField.class);
        org.dromara.common.encrypt.core.EncryptContext ctx = new org.dromara.common.encrypt.core.EncryptContext();
        ctx.setAlgorithm(anno.algorithm());
        ctx.setPassword(anno.password());
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
