package com.smartdock.assistant.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdock.assistant.domain.model.McpServer;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.encrypt.core.EncryptContext;
import org.dromara.common.encrypt.core.EncryptorManager;
import org.dromara.common.encrypt.enumd.AlgorithmType;
import org.dromara.common.encrypt.enumd.EncodeType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP 请求头的显式加解密（批 5 题 3 案 1，2026-11-19：从 legacy 的 McpPilotService 抽出）。
 *
 * <p>为什么独立成件：保存服务行必须加密、回显只出键名、挂载前必须解密——三处都要它，
 * 而新根 service 不许 import 旧根（规则 8）。抽成 support 后新旧两侧共用同一份实现，
 * 加密密钥的读取点也只有这一处（密钥铁律：只从 SMARTDOCK_MCP_HEADERS_KEY 注入）。</p>
 *
 * <p>G4-#1 实测坑（2026-10-22 活库 g4htest 行实锤）：@EncryptField 注解在、启动扫描单测过，
 * 但运行时插入仍明文——安全字段不赌隐式装配：服务层显式加解密（EncryptorManager 同款算法
 * 与密钥），对 ENC_ 前缀幂等——无论拦截器将来是否生效，双轨都安全不重不漏。</p>
 */
@Slf4j
@Component
public class McpHeaderCipher {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** 加密组件可能未启用（mybatis-encryptor.enable=false）——懒取，缺失时 fail-loud */
    private final ObjectProvider<EncryptorManager> encryptorManagerProvider;

    /** 请求头加密密钥从环境变量读（smartdock.encrypt.mcp-headers-key） */
    private final Environment environment;

    public McpHeaderCipher(ObjectProvider<EncryptorManager> encryptorManagerProvider, Environment environment) {
        this.encryptorManagerProvider = encryptorManagerProvider;
        this.environment = environment;
    }

    /** 明文 JSON → ENC_ 密文；空/{} 直通（无敏感面），已带 ENC_ 前缀幂等直通 */
    public String encrypt(String plainJson) {
        if (plainJson == null || plainJson.isBlank() || plainJson.trim().equals("{}")
            || plainJson.startsWith("ENC_")) {
            return plainJson;
        }
        EncryptorManager m = encryptorManagerProvider.getIfAvailable();
        if (m == null) {
            // 安全字段拒绝明文落库，fail-loud
            throw new ServiceException("加密组件未启用（mybatis-encryptor.enable），无法保存请求头");
        }
        return m.encrypt(plainJson, headersEncryptContext());
    }

    /**
     * ENC_ 密文 → 明文 JSON；历史明文/空原样返回（幂等兼容）。
     *
     * <p>2026-11-02：加密组件缺失时不再原样返回密文——否则挂载会把 "ENC_..." 当请求头发出去。</p>
     */
    public String decrypt(String storedJson) {
        if (storedJson == null || !storedJson.startsWith("ENC_")) {
            return storedJson;
        }
        EncryptorManager m = encryptorManagerProvider.getIfAvailable();
        if (m == null) {
            throw new ServiceException("加密组件未启用（mybatis-encryptor.enable），无法读取 MCP 请求头");
        }
        return m.decrypt(storedJson, headersEncryptContext());
    }

    /**
     * 回显键名（逗连）——解密后取；管理面列表唯一出口（值永不出库到前端）。
     *
     * <p>G4 三审回修（dsflash P2-5）：单行坏密文不得 500 整个列表——解密失败降级
     * "(解析失败)"，管理面仍可看到该行并修复（日志带服务名便于定位坏行）。</p>
     */
    public String headerKeysOf(McpServer row) {
        String storedJson = row == null ? null : row.getHeaders();
        String json;
        try {
            json = decrypt(storedJson);
        } catch (Exception e) {
            log.warn("[McpHeaderCipher] headers 解密失败（键名降级）：server={}, err={}",
                row == null ? null : row.getServerName(), e.getMessage());
            return "(解析失败)";
        }
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> map = JSON_MAPPER.readValue(json, Map.class);
            return map == null ? "" : String.join(",", map.keySet());
        } catch (JsonProcessingException e) {
            return "(解析失败)";
        }
    }

    private EncryptContext headersEncryptContext() {
        String key = environment.getProperty("smartdock.encrypt.mcp-headers-key", "");
        if (key == null || key.isBlank()) {
            // 启动自检已挡；这里是防御性兜底（禁止空密钥加密）
            throw new ServiceException("MCP 请求头加密密钥未配置（SMARTDOCK_MCP_HEADERS_KEY）");
        }
        EncryptContext c = new EncryptContext();
        c.setAlgorithm(AlgorithmType.AES);
        c.setPassword(key);
        c.setEncode(EncodeType.BASE64);
        return c;
    }
}
