package com.smartdock.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 配置连通性测试结果。
 *
 * @author smartdock
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DcsAiTestResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 批C C2 P2-1：结果归属的配置行（批量体检时前端按 configId 对位，不再按数组下标
     *  猜——同厂商多模型时下标错位会把结果贴到别的行） */
    private Long configId;

    private Boolean success;

    private String message;

    private Long latencyMs;

    public DcsAiTestResultVo(Boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    /** 显式三参（成功+消息+耗时）：新增 configId 字段后 @AllArgsConstructor 变四参，
     *  既有调用点（testConnection/probe）语义就是这三样，保留显式构造防破坏 */
    public DcsAiTestResultVo(Boolean success, String message, Long latencyMs) {
        this.success = success;
        this.message = message;
        this.latencyMs = latencyMs;
    }
}
