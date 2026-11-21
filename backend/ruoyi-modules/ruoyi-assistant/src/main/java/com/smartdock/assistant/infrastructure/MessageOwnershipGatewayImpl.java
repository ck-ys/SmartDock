package com.smartdock.assistant.infrastructure;

import com.smartdock.assistant.domain.gateway.MessageOwnershipGateway;
import com.smartdock.assistant.domain.model.AssistantMessage;
import com.smartdock.assistant.mapper.AssistantMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 消息归属网关实现（批 6 题 3 案 1，2026-11-20）：查 legacy 消息表，判租户 + 用户双匹配。
 *
 * <p>放在 infrastructure 是规则 8 的落点——legacy 依赖只允许出现在这一层；
 * 新根 service 只认 {@link MessageOwnershipGateway} 接口。</p>
 */
@Component
@RequiredArgsConstructor
public class MessageOwnershipGatewayImpl implements MessageOwnershipGateway {

    private final AssistantMessageMapper messageMapper;

    @Override
    public boolean ownedBy(String tenantId, Long userId, Long messageId) {
        if (messageId == null) {
            return false;
        }
        AssistantMessage msg = messageMapper.selectById(messageId);
        return msg != null
            && Objects.equals(msg.getTenantId(), tenantId)
            && Objects.equals(msg.getUserId(), userId);
    }
}
