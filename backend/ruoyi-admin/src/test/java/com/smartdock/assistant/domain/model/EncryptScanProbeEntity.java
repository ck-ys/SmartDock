package com.smartdock.assistant.domain.model;

import org.dromara.common.encrypt.annotation.EncryptField;

/**
 * 加密扫描探针（批 1 多审席 B P1，2026-11-17）：只存在于 test classpath，
 * {@code G4EncryptScanTest} 用它证明 {@code domain/model} 子包里的 @EncryptField
 * 实体仍会被 EncryptorManager 的 typeAliasesPackage 扫描收进缓存——
 * yml 只写 {@code com.smartdock.**.domain}（单星 *.class）时本探针扫不到=明文落库隐患。
 */
public class EncryptScanProbeEntity {

    @EncryptField(password = "${smartdock.encrypt.probe-key}")
    private String secretField;

    public String getSecretField() {
        return secretField;
    }
}
