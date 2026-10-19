package org.dromara.common.web.handler;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.validation.FieldError;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug7(2026-09-20 夜审)锁死测试: 分页参数 pageSize=abc 走 BO 绑定时 defaultMessage 是
 * 无 "property" 变体("Failed to convert value of type..."), 旧前缀判定漏掉它导致英文原文
 * 透出给业务用户——前缀已放宽为 "Failed to convert", 此处锁死三种行为不回退。
 */
@Tag("dev")
class GlobalExceptionHandlerBindingMessageUnitTest {

    @Test
    void conversionFailureWithoutPropertyPrefixGetsChineseFallback() throws Exception {
        FieldError error = new FieldError("pageQuery", "pageSize", "abc", true,
            new String[]{"typeMismatch.java.lang.Integer", "typeMismatch.pageSize"}, null,
            "Failed to convert value of type 'java.lang.String' to required type 'java.lang.Integer'");
        assertThat(invoke(error)).startsWith("输入内容格式不正确").contains("数字");
    }

    /** 0919 业务反馈#6 既有行为(带 property 变体)不回退 */
    @Test
    void conversionFailureWithPropertyPrefixStillChinese() throws Exception {
        FieldError error = new FieldError("bo", "missionId", "中文", true,
            new String[]{"typeMismatch.java.lang.Long"}, null,
            "Failed to convert property value of type 'java.lang.String' to required type 'java.lang.Long'");
        assertThat(invoke(error)).startsWith("输入内容格式不正确").contains("数字");
    }

    /** 非类型转换错误(校验注解的中文消息)维持 defaultMessage 透传, 不被前缀放宽误吞 */
    @Test
    void nonConversionValidationErrorKeepsDefaultMessage() throws Exception {
        FieldError error = new FieldError("bo", "missionName", null, true, null, null, "任务名称不能为空");
        assertThat(invoke(error)).isEqualTo("任务名称不能为空");
    }

    private String invoke(FieldError error) throws Exception {
        Method method = GlobalExceptionHandler.class.getDeclaredMethod("resolveBindingErrorMessage", List.class);
        method.setAccessible(true);
        return (String) method.invoke(new GlobalExceptionHandler(), List.of(error));
    }
}
