package org.dromara.common.web.handler;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.http.HttpStatus;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.exception.SseException;
import org.dromara.common.core.exception.base.BaseException;
import org.dromara.common.core.utils.StreamUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.boot.json.JsonParseException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.expression.ExpressionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.io.IOException;

/**
 * 全局异常处理器
 *
 * @author Lion Li
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 请求方式不支持
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<Void> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                       HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',不支持'{}'请求", requestURI, e.getMethod());
        return R.fail(HttpStatus.HTTP_BAD_METHOD, e.getMessage());
    }

    /**
     * 业务异常
     */
    @ExceptionHandler(ServiceException.class)
    public R<Void> handleServiceException(ServiceException e, HttpServletRequest request) {
        log.error(e.getMessage());
        Integer code = e.getCode();
        return ObjectUtil.isNotNull(code) ? R.fail(code, e.getMessage()) : R.fail(e.getMessage());
    }

    /**
     * 认证失败
     */
    @ResponseStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(SseException.class)
    public String handleNotLoginException(SseException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.debug("请求地址'{}',认证失败'{}',无法访问系统资源", requestURI, e.getMessage());
        return JsonUtils.toJsonString(R.fail(HttpStatus.HTTP_UNAUTHORIZED, "认证失败，无法访问系统资源"));
    }

    /**
     * servlet异常
     */
    @ExceptionHandler(ServletException.class)
    public R<Void> handleServletException(ServletException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生未知异常.", requestURI, e);
        return R.fail("发生未知异常，请联系管理员");
    }

    /**
     * 业务异常
     */
    @ExceptionHandler(BaseException.class)
    public R<Void> handleBaseException(BaseException e, HttpServletRequest request) {
        log.error(e.getMessage());
        return R.fail(e.getMessage());
    }

    /**
     * 请求路径中缺少必需的路径变量
     */
    @ExceptionHandler(MissingPathVariableException.class)
    public R<Void> handleMissingPathVariableException(MissingPathVariableException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求路径中缺少必需的路径变量'{}',发生系统异常.", requestURI);
        return R.fail(String.format("请求路径中缺少必需的路径变量[%s]", e.getVariableName()));
    }

    /**
     * 请求参数类型不匹配
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求参数类型不匹配'{}',发生系统异常, 参数[{}]要求类型[{}]输入值[{}].",
            requestURI, e.getName(), e.getRequiredType(), e.getValue());
        // 0919 业务反馈#6 拍板A: 路径/查询参数类型不匹配同样给中文兜底, 不暴露参数名与 Java 类型名
        return R.fail("输入内容格式不正确（此处要求" + friendlyRequirement(e.getRequiredType()) + "），请检查后重试");
    }

    /**
     * 找不到路由
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public R<Void> handleNoHandlerFoundException(NoHandlerFoundException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}'不存在.", requestURI);
        return R.fail(HttpStatus.HTTP_NOT_FOUND, "请求地址不存在");
    }

    /**
     * 拦截未知的运行时异常
     */
    @ResponseStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(IOException.class)
    public void handleIoException(IOException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        if (requestURI.contains("sse")) {
            // sse 经常性连接中断 例如关闭浏览器 直接屏蔽
            return;
        }
        log.error("请求地址'{}',连接中断", requestURI, e);
    }

    /**
     * sse 连接超时异常 不需要处理
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleRuntimeException(AsyncRequestTimeoutException e) {
    }

    /**
     * 拦截未知的运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public R<Void> handleRuntimeException(RuntimeException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生未知异常.", requestURI, e);
        return R.fail("发生未知异常，请联系管理员");
    }

    /**
     * 系统异常
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}',发生系统异常.", requestURI, e);
        return R.fail("发生系统异常，请联系管理员");
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        log.error(e.getMessage());
        return R.fail(resolveBindingErrorMessage(e.getAllErrors()));
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public R<Void> constraintViolationException(ConstraintViolationException e) {
        log.error(e.getMessage());
        String message = StreamUtils.join(e.getConstraintViolations(), ConstraintViolation::getMessage, ", ");
        return R.fail(message);
    }

    /**
     * 自定义验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error(e.getMessage());
        // 0919 业务反馈#6: @Validated @ModelAttribute 的 GET 查询参数绑定失败(如任务ID输中文)实际
        // 抛 MethodArgumentNotValidException 而非 BindException——两个入口走同一转换, 保证中文兜底全覆盖
        return R.fail(resolveBindingErrorMessage(e.getBindingResult().getAllErrors()));
    }

    /**
     * 0919 业务反馈#6 拍板A: 数字/日期参数传非法值时 Spring 默认消息是英文长串
     * (Failed to convert property value...), 业务用户完全看不懂——类型转换失败给中文兜底;
     * 不暴露参数名(字段名是开发侧概念); 其余错误(校验注解消息本身多为中文)维持默认消息。
     * 判定用 defaultMessage 前缀而非 code=typeMismatch(实测 BO 绑定路径 code 不稳定)。
     * 前缀放宽为 "Failed to convert"(2026-09-20 Bug7): 分页参数 pageSize=abc 走 BO 绑定时
     * defaultMessage 是无 "property" 变体("Failed to convert value of type...")——旧前缀
     * 匹配不上导致英文原文透出给业务用户, 两种变体统一进中文兜底。
     */
    private String resolveBindingErrorMessage(java.util.List<? extends org.springframework.context.MessageSourceResolvable> errors) {
        return errors.stream()
            .map(err -> {
                if (err instanceof org.springframework.validation.FieldError fieldError
                    && fieldError.getRejectedValue() != null
                    && !String.valueOf(fieldError.getRejectedValue()).isEmpty()
                    && fieldError.getDefaultMessage() != null
                    && fieldError.getDefaultMessage().startsWith("Failed to convert")) {
                    return "输入内容格式不正确（此处要求" + friendlyRequirement(fieldError) + "），请检查后重试";
                }
                return err.getDefaultMessage();
            })
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private String friendlyRequirement(org.springframework.validation.FieldError fieldError) {
        // 三审 P2: codes 里还混有 "typeMismatch.bo.<字段名>" 段, 全量子串匹配会被字段名带偏
        // (字段叫 LongXxx 会被误判成"数字")——只认 "typeMismatch.java.<类型>" 类型段
        String typeToken = fieldError.getCodes() == null ? "" : java.util.Arrays.stream(fieldError.getCodes())
            .filter(code -> code != null && code.startsWith("typeMismatch.java."))
            .findFirst().orElse("");
        if (typeToken.contains("Long") || typeToken.contains("Integer") || typeToken.contains(".int")
            || typeToken.contains("Short") || typeToken.contains("Double") || typeToken.contains("Float")
            || typeToken.contains("BigDecimal") || typeToken.contains("BigInteger")) {
            return "数字";
        }
        if (typeToken.contains("Date") || typeToken.contains("LocalDateTime") || typeToken.contains("LocalDate")) {
            return "日期";
        }
        return "合法格式";
    }

    /** 0919 业务反馈#6 拍板A: 路径/查询参数版——按目标类型归并为 数字/日期/合法格式 */
    private String friendlyRequirement(Class<?> requiredType) {
        if (requiredType == null) {
            return "合法格式";
        }
        if (Number.class.isAssignableFrom(requiredType)) {
            return "数字";
        }
        if (java.util.Date.class.isAssignableFrom(requiredType)
            || java.time.temporal.Temporal.class.isAssignableFrom(requiredType)) {
            return "日期";
        }
        return "合法格式";
    }

    /**
     * 方法参数校验异常 用于处理 @Validated 注解
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public R<Void> handlerMethodValidationException(HandlerMethodValidationException e) {
        log.error(e.getMessage());
        String message = StreamUtils.join(e.getAllErrors(), MessageSourceResolvable::getDefaultMessage, ", ");
        return R.fail(message);
    }

    /**
     * JSON 解析异常（Jackson 在处理 JSON 格式出错时抛出）
     * 可能是请求体格式非法，也可能是服务端反序列化失败
     */
    @ExceptionHandler(JsonParseException.class)
    public R<Void> handleJsonParseException(JsonParseException e, HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        log.error("请求地址'{}' 发生 JSON 解析异常: {}", requestURI, e.getMessage());
        // 0919 业务反馈#6 拍板A延伸: Jackson 英文原文只进日志, 用户消息中文兜底
        return R.fail(HttpStatus.HTTP_BAD_REQUEST, "请求内容格式不正确，请检查填写内容后重试");
    }

    /**
     * 请求体读取异常（通常是请求参数格式非法、字段类型不匹配等）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.error("请求地址'{}', 参数解析失败: {}", request.getRequestURI(), e.getMostSpecificCause().getMessage());
        // 0919 业务反馈#6 拍板A延伸: JSON 请求体类型错(如数字字段传中文)此前直接透出
        // "Cannot deserialize value of type java.lang.Long..." 英文串, 业务用户完全看不懂
        return R.fail(HttpStatus.HTTP_BAD_REQUEST, "输入内容格式不正确，请检查填写内容后重试");
    }

    /**
     * SpEL 表达式相关异常
     */
    @ExceptionHandler(ExpressionException.class)
    public R<Void> handleSpelException(ExpressionException e, HttpServletRequest request) {
        log.error("请求地址'{}'，SpEL解析异常: {}", request.getRequestURI(), e.getMessage());
        return R.fail(HttpStatus.HTTP_INTERNAL_ERROR, "SpEL解析失败：" + e.getMessage());
    }

}
