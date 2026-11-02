package org.dromara.web.runner;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 加密密钥启动自检（2026-11-02 密钥整改铁律）。
 *
 * <p>背景：{@code @EncryptField} 的密钥曾经写死在源码里（AI 配置一把、MCP 请求头一把），
 * 都随公开仓泄露；拦截器又会 catch 掉加密异常继续写入——2026-10-22 首版 14 位密钥
 * 就是这样变成“明文落库”的。所以密钥缺失/长度错/仍在用旧钥匙时，必须在启动阶段
 * 直接拒绝服务，绝不带着坏钥匙跑。</p>
 *
 * <p>本类只做校验，不打印密钥内容，也不改写任何配置。</p>
 */
@Slf4j
@Component
public class EncryptKeyStartupRunner {

    public static final String AI_KEY_PROP = "smartdock.encrypt.ai-config-key";
    public static final String MCP_KEY_PROP = "smartdock.encrypt.mcp-headers-key";

    /** 旧版源码写死、已随公开仓泄露的两把钥匙：只允许迁移逻辑拿去解密历史数据，禁止再用于加密 */
    static final Set<String> BURNED_KEYS = Set.of("DcsAiCfgEncKey16", "DcsMcpHeadersK16");

    private static final int[] VALID_LENGTHS = {16, 24, 32};

    private final Environment environment;

    public EncryptKeyStartupRunner(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void check() {
        Boolean enabled = environment.getProperty("mybatis-encryptor.enable", Boolean.class, Boolean.TRUE);
        if (!Boolean.TRUE.equals(enabled)) {
            throw new IllegalStateException(
                "[密钥自检] mybatis-encryptor.enable=false：AI Key 与 MCP 请求头会明文落库，拒绝启动。"
                    + "请开启加密后重启。");
        }
        String ai = environment.getProperty(AI_KEY_PROP, "");
        String mcp = environment.getProperty(MCP_KEY_PROP, "");
        validate("SMARTDOCK_AI_CONFIG_KEY", AI_KEY_PROP, ai);
        validate("SMARTDOCK_MCP_HEADERS_KEY", MCP_KEY_PROP, mcp);
        if (ai.equals(mcp)) {
            throw new IllegalStateException(
                "[密钥自检] 两把密钥必须不同：AI 配置与 MCP 请求头应各用一把独立密钥。");
        }
        log.info("[密钥自检] 通过：AI 配置密钥与 MCP 请求头密钥均已配置（不打印密钥内容）");
    }

    /**
     * 单把密钥的校验（public 便于单测直接调用）。
     */
    public static void validate(String envName, String propName, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                "[密钥自检] 缺少环境变量 " + envName + "（配置项 " + propName + "）。"
                    + "生成方法：openssl rand -base64 24（得到 32 位），设置为环境变量后重启。");
        }
        if (!key.equals(key.trim())) {
            throw new IllegalStateException(
                "[密钥自检] " + envName + " 首尾含空白字符（常见于复制时带了换行），请去掉后重启。");
        }
        boolean validLength = false;
        for (int len : VALID_LENGTHS) {
            if (key.length() == len) {
                validLength = true;
                break;
            }
        }
        if (!validLength) {
            throw new IllegalStateException(
                "[密钥自检] " + envName + " 长度必须为 16/24/32 位（当前 " + key.length()
                    + " 位）。生成方法：openssl rand -base64 24。");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                throw new IllegalStateException(
                    "[密钥自检] " + envName + " 只能包含可见 ASCII 字符（不能有空格/中文/全角符号）。");
            }
        }
        if (BURNED_KEYS.contains(key)) {
            throw new IllegalStateException(
                "[密钥自检] " + envName + " 仍是旧版源码里写死的钥匙（已随公开仓泄露），"
                    + "禁止继续使用——请生成新密钥后重启。");
        }
    }
}
