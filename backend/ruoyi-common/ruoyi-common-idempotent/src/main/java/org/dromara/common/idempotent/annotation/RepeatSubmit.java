package org.dromara.common.idempotent.annotation;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 自定义注解防止表单重复提交
 *
 * @author Lion Li
 */
@Inherited
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RepeatSubmit {

    // ===== DCS 调用指南 =====
    // DCS 引用频次: 20+ 个文件
    // 典型用法: DCS 20 个写接口(新增/修改/删除/导入)用 @RepeatSubmit 防重复提交; 默认 interval=5000ms, 即 5 秒内相同请求参数视为重复并拦截
    // 扩展点: DCS 所有 POST/PUT/DELETE Controller 方法建议加 @RepeatSubmit, 防止前端双击或网络重试导致重复写入
    // ===== DCS 调用指南 结束 =====

    /**
     * 间隔时间(ms)，小于此时间视为重复提交
     */
    int interval() default 5000;

    TimeUnit timeUnit() default TimeUnit.MILLISECONDS;

    /**
     * 提示消息 支持国际化 格式为 {code}
     */
    String message() default "{repeat.submit.message}";

}
