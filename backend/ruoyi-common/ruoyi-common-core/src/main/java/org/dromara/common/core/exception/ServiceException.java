package org.dromara.common.core.exception;

import cn.hutool.core.text.StrFormatter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serial;

/**
 * 业务异常（支持占位符 {} ）
 *
 * @author ruoyi
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public final class ServiceException extends RuntimeException {
    // ===== DCS 调用指南 =====
    // DCS 引用频次: 76+ 个文件(全模块最高频)
    // 典型用法: throw new ServiceException("字段 [" + name + "] 为必填"); 全局异常处理器捕获后转 R.fail
    // DCS 约定: code 通常省略(默认 500); 403/409 等特殊 HTTP 语义时显式传 code
    //   - 403 越权(如审核人不是当前责任人)
    //   - 409 冲突(如转发幂等 CAS 失败)
    // ⚠️ 占位符 {} 用 hutool StrFormatter, 不是 SLF4J 的 {}: new ServiceException("第{}行", row)
    // ===== DCS 调用指南 结束 =====

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private Integer code;

    /**
     * 错误提示
     */
    private String message;

    /**
     * 错误明细，内部调试错误
     */
    private String detailMessage;

    /** 仅提示信息, 错误码默认 500 */
    public ServiceException(String message) {
        this.message = message;
    }

    /** 提示信息 + 自定义错误码(如 403 越权 / 409 冲突) */
    public ServiceException(String message, Integer code) {
        this.message = message;
        this.code = code;
    }

    /**
     * 占位符格式化构造器: 用 hutool {@link StrFormatter#format} 把 {@code {}} 替换为参数。
     *
     * <p>注意: 占位符是单大括号 {@code {}}(不是 SLF4J 的 {@code {}}), 参数按序填充。</p>
     *
     * @param message 含 {@code {}} 占位符的模板, 如 {@code "第{}行字段[{}]格式不正确"}
     * @param args    按序替换 {@code {}} 的参数
     */
    public ServiceException(String message, Object... args) {
        this.message = StrFormatter.format(message, args);
    }

    @Override
    public String getMessage() {
        return message;
    }

    /** 链式设置提示信息(用于 setMessage(...).setDetailMessage(...) 链式调用) */
    public ServiceException setMessage(String message) {
        this.message = message;
        return this;
    }

    /** 链式设置调试明细(不直接返回给前端, 仅日志/调试用) */
    public ServiceException setDetailMessage(String detailMessage) {
        this.detailMessage = detailMessage;
        return this;
    }
}
