package com.smartdock.assistant.spi;

import lombok.Data;

/**
 * 工具执行结果。
 *
 * @author smartdock
 */
@Data
public class BizToolResult {

    private boolean ok;

    /** ok=true 时的负载数据(桥统一 JSON 序列化喂给模型; null → 空对象) */
    private Object data;

    /** ok=false 时模型可读的错误说明(不抛异常) */
    private String message;

    public static BizToolResult ok(Object data) {
        BizToolResult r = new BizToolResult();
        r.setOk(true);
        r.setData(data);
        return r;
    }

    public static BizToolResult fail(String message) {
        BizToolResult r = new BizToolResult();
        r.setOk(false);
        r.setMessage(message);
        return r;
    }
}
