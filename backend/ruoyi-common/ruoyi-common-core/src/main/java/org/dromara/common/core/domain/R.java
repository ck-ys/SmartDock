package org.dromara.common.core.domain;

import org.dromara.common.core.constant.HttpStatus;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

import java.io.Serial;
import java.io.Serializable;

/**
 * 响应信息主体
 *
 * @author Lion Li
 */
@Data
@NoArgsConstructor
public class R<T> implements Serializable {

    // ===== DCS 调用指南 =====
    // DCS 引用频次: 37+ 个文件
    // 典型用法: DCS 统一响应体 R.ok(data)/R.fail(msg)/R.warn(msg); code=200 成功, 500 失败, 601(HttpStatus.WARN) 表示部分成功/警告
    // ⚠️ DCS 踩过的坑: traceId 仅 HTTP 请求线程有值(MDC), MQ/定时任务线程为 null, 排查链路时需手动传 traceId
    // 扩展点: 所有 DCS Controller 返回值统一用 R<T>, 前端按 code 字段判断成功失败
    // ===== DCS 调用指南 结束 =====

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 成功
     */
    public static final int SUCCESS = 200;

    /**
     * 失败
     */
    public static final int FAIL = 500;

    private int code;

    private String msg;

    private T data;

    /** 链路追踪 ID (从 MDC 读取, 供前端关联日志; 非 HTTP 请求线程为 null) */
    private String traceId;

    public static <T> R<T> ok() {
        return restResult(null, SUCCESS, "操作成功");
    }

    public static <T> R<T> ok(T data) {
        return restResult(data, SUCCESS, "操作成功");
    }

    public static <T> R<T> ok(String msg) {
        return restResult(null, SUCCESS, msg);
    }

    public static <T> R<T> ok(String msg, T data) {
        return restResult(data, SUCCESS, msg);
    }

    public static <T> R<T> fail() {
        return restResult(null, FAIL, "操作失败");
    }

    public static <T> R<T> fail(String msg) {
        return restResult(null, FAIL, msg);
    }

    public static <T> R<T> fail(T data) {
        return restResult(data, FAIL, "操作失败");
    }

    public static <T> R<T> fail(String msg, T data) {
        return restResult(data, FAIL, msg);
    }

    public static <T> R<T> fail(int code, String msg) {
        return restResult(null, code, msg);
    }

    /**
     * 返回警告消息
     *
     * @param msg 返回内容
     * @return 警告消息
     */
    public static <T> R<T> warn(String msg) {
        return restResult(null, HttpStatus.WARN, msg);
    }

    /**
     * 返回警告消息
     *
     * @param msg 返回内容
     * @param data 数据对象
     * @return 警告消息
     */
    public static <T> R<T> warn(String msg, T data) {
        return restResult(data, HttpStatus.WARN, msg);
    }

    private static <T> R<T> restResult(T data, int code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setData(data);
        r.setMsg(msg);
        // 从 MDC 读取 traceId (TraceIdFilter 写入), 非 HTTP 请求线程为 null
        r.setTraceId(MDC.get("traceId"));
        return r;
    }

    public static <T> Boolean isError(R<T> ret) {
        return !isSuccess(ret);
    }

    public static <T> Boolean isSuccess(R<T> ret) {
        return R.SUCCESS == ret.getCode();
    }
}
