package com.smartdock.assistant.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.smartdock.assistant.domain.model.AssistantConversation;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

/**
 * 会话元数据写（批 7 · 7B，2026-11-20 拍板 Q2 ★ 按用途三分）。
 *
 * <p><b>纯搬移</b>：归档 / 重命名 / 恢复三个方法与 {@code AssistantConversationService}
 * 原实现逐行一致——先 selectOwned 归属校验（异主/不存在一律 403，不区分防探测），
 * 再按 (tenant_id, id) 条件 UPDATE；标题 1-60 字符校验文案与错误码不变。</p>
 *
 * <p>这批只动元数据（title/status），不碰消息正文、run 记录、purge 与保留期清理。</p>
 */
@Service
@RequiredArgsConstructor
public class ConversationMetaService {

    private final AssistantConversationMapper conversationMapper;

    /** 本人归档会话（软通道：status=ARCHIVED） */
    public void archiveConversation(String tenantId, Long userId, Long conversationId) {
        AssistantConversation conv = conversationMapper.selectOwned(tenantId, conversationId, userId);
        if (conv == null) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        conversationMapper.update(null, new LambdaUpdateWrapper<AssistantConversation>()
            .eq(AssistantConversation::getTenantId, tenantId)
            .eq(AssistantConversation::getId, conversationId)
            .set(AssistantConversation::getStatus, "ARCHIVED"));
    }

    /** 本人重命名会话（批D P2-7：PATCH title；title 列 varchar(200) 已有，零 DDL） */
    public void renameConversation(String tenantId, Long userId, Long conversationId, String title) {
        String safe = title == null ? "" : title.trim();
        if (safe.isEmpty() || safe.length() > 60) {
            throw new ServiceException("标题须为 1-60 个字符", 400);
        }
        AssistantConversation conv = conversationMapper.selectOwned(tenantId, conversationId, userId);
        if (conv == null) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        conversationMapper.update(null, new LambdaUpdateWrapper<AssistantConversation>()
            .eq(AssistantConversation::getTenantId, tenantId)
            .eq(AssistantConversation::getId, conversationId)
            .set(AssistantConversation::getTitle, safe));
    }

    /** 恢复已归档会话（批D：status=ARCHIVED→ACTIVE） */
    public void restoreConversation(String tenantId, Long userId, Long conversationId) {
        AssistantConversation conv = conversationMapper.selectOwned(tenantId, conversationId, userId);
        if (conv == null) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        conversationMapper.update(null, new LambdaUpdateWrapper<AssistantConversation>()
            .eq(AssistantConversation::getTenantId, tenantId)
            .eq(AssistantConversation::getId, conversationId)
            .set(AssistantConversation::getStatus, "ACTIVE"));
    }
}
