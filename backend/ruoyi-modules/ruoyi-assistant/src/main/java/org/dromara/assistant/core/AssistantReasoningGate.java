package org.dromara.assistant.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 思考内容展示闸（批A Task A3；思考链路批 2026-10-08 默认改开）。
 *
 * <p>三层设计的第一层 yml enable-reasoning=true 恒开（数据到达服务端），
 * 本闸是第二层：关=enricher 丢内容帧只留结构标记（前端显示"思考中"占位），
 * 开=思考内容全放行。<b>2026-10-08 用户报障「思考内容一点也不显示」破案：
 * 本闸默认 false 且全仓无任何开启入口（管理端点/单用户偏好均未落地），
 * 内容帧被服务端全部丢弃——默认改开，yml {@code dcs.assistant.reasoning-display.enabled}
 * 可全局关停（运维开关），展示与否交前端「过程显示」偏好（T-2）分层控制。</p>
 */
@Component
public class AssistantReasoningGate {

    @Value("${dcs.assistant.reasoning-display.enabled:true}")
    private volatile boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
