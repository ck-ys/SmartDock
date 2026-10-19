package org.dromara.common.encrypt.annotation;

import org.dromara.common.encrypt.enumd.AlgorithmType;
import org.dromara.common.encrypt.enumd.EncodeType;

import java.lang.annotation.*;

/**
 * 字段加密注解
 *
 * @author 老马
 */
@Documented
@Inherited
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface EncryptField {

    // ===== DCS 调用指南 =====
    // DCS 引用频次: 4+ 个文件
    // 典型用法: DCS AI 配置的 API 密钥(apiKey/secretKey)用 @EncryptField 加密存储到 DB; 查询时通过 MyBatis 拦截器自动解密, 业务代码无感知
    // ⚠️ DCS 踩过的坑: LIKE 模糊查询加密字段不生效(密文每次不同), 等值查询才有效; 加密字段不建议做查询条件
    // ===== DCS 调用指南 结束 =====

    /**
     * 加密算法
     */
    AlgorithmType algorithm() default AlgorithmType.DEFAULT;

    /**
     * 秘钥。AES、SM4需要
     */
    String password() default "";

    /**
     * 公钥。RSA、SM2需要
     */
    String publicKey() default "";

    /**
     * 私钥。RSA、SM2需要
     */
    String privateKey() default "";

    /**
     * 编码方式。对加密算法为BASE64的不起作用
     */
    EncodeType encode() default EncodeType.DEFAULT;

}
