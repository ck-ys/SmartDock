package org.dromara.common.translation.annotation;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.dromara.common.translation.core.handler.TranslationHandler;

import java.lang.annotation.*;

/**
 * 通用翻译注解
 *
 * @author Lion Li
 */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
@Documented
@JacksonAnnotationsInside
@JsonSerialize(using = TranslationHandler.class)
public @interface Translation {

    // ===== DCS 调用指南 =====
    // DCS 引用频次: 9+ 个文件
    // 典型用法: DCS VO 字段翻译(如 status→中文标签, userId→昵称); 配合 TransConstant 常量标注 type; 序列化返回前端时自动替换为翻译值
    // 扩展点: 新增翻译类型需实现 TranslationInterface 接口并在 TransConstant 增加常量, VO 字段标注 @Translation(type=..., other=...)
    // ===== DCS 调用指南 结束 =====

    /**
     * 类型 (需与实现类上的 {@link TranslationType} 注解type对应)
     * <p>
     * 默认取当前字段的值 如果设置了 @{@link Translation#mapper()} 则取映射字段的值
     */
    String type();

    /**
     * 映射字段 (如果不为空则取此字段的值)
     */
    String mapper() default "";

    /**
     * 其他条件 例如: 字典type(sys_user_sex)
     */
    String other() default "";

}
