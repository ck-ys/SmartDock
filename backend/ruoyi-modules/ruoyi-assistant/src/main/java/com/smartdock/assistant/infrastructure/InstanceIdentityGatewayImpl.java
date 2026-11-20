package com.smartdock.assistant.infrastructure;

import com.smartdock.assistant.domain.gateway.InstanceIdentityGateway;
import com.smartdock.assistant.legacy.core.AssistantInstanceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link InstanceIdentityGateway} 的唯一实现（批 7 · 7C-1，2026-11-20）。
 *
 * <p>实例身份（id 生成、Redis 租约占位、心跳）整套仍在 legacy {@code AssistantInstanceContext}，
 * 批 9 随引擎收口。本实现是唯一跨线点：只透传 {@code current()}，不复制任何生成逻辑——
 * 若在这里另算一遍 id，登记写进 run 行的 owner 与心跳续租的 key 就会对不上，
 * 收割器会把本机活 run 判成「别人的已死 run」。</p>
 */
@Component
@RequiredArgsConstructor
public class InstanceIdentityGatewayImpl implements InstanceIdentityGateway {

    private final AssistantInstanceContext instanceContext;

    @Override
    public String currentInstanceId() {
        return instanceContext.current();
    }
}
