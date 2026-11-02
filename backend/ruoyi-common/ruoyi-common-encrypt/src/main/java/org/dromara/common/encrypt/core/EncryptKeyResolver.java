package org.dromara.common.encrypt.core;

import org.dromara.common.core.utils.StringUtils;
import org.springframework.core.env.Environment;

/**
 * {@code @EncryptField} 密钥占位符解析（2026-11-02 密钥整改）。
 *
 * <p>背景：注解参数是编译期常量，读不到运行时环境变量，导致密钥只能写死在源码里
 * （两把钥匙随公开仓泄露）。本解析器让注解可以写 {@code ${smartdock.encrypt.xxx}}
 * 这类占位符，由 Spring Environment 在运行时换成真实值。</p>
 *
 * <p>安全口径：占位符没配 → 直接抛错，绝不回退成空串/字面量。公共模块里的加密拦截器
 * 原本会 catch 掉加密异常并继续，结果是明文落库（2026-10-22 的 14 位密钥事故）；
 * 因此调用方必须把本解析放在 try 之外，让配置错误一定炸出来。</p>
 */
public final class EncryptKeyResolver {

    private EncryptKeyResolver() {
    }

    /**
     * 解析注解上的密钥：空 → 用全局默认值；含 {@code ${...}} → 走 Environment 解析并要求非空；
     * 普通字面量 → 原样返回（向后兼容框架原有写法）。
     *
     * @param environment   Spring 环境
     * @param annotated     注解上写的值
     * @param defaultValue  全局默认值（mybatis-encryptor.password）
     * @return 解析后的真实密钥
     */
    public static String resolve(Environment environment, String annotated, String defaultValue) {
        String raw = StringUtils.isBlank(annotated) ? defaultValue : annotated;
        if (StringUtils.isBlank(raw)) {
            return raw;
        }
        if (!raw.contains("${")) {
            return raw;
        }
        String resolved = environment.resolveRequiredPlaceholders(raw);
        if (StringUtils.isBlank(resolved)) {
            throw new IllegalStateException(
                "加密密钥占位符未配置：" + raw + "（请设置对应环境变量；安全字段禁止回退明文）");
        }
        return resolved;
    }
}
