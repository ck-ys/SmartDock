package com.smartdock.assistant.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.assistant.domain.gateway.RunStatusGateway;
import com.smartdock.assistant.domain.model.DcsAssistantConversation;
import com.smartdock.assistant.domain.model.DcsAssistantMessage;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import com.smartdock.assistant.mapper.AssistantMessageMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 会话与消息的只读查询（批 7 · 7B，2026-11-20 拍板 Q2 ★ 按用途三分）。
 *
 * <p><b>纯搬移</b>：口径与 {@code AssistantConversationService} 原同名方法逐行一致
 * （列表排序 id 决胜、status 收敛、limit/offset 由调用方夹紧、消息归属 403、尾页反转）；
 * 唯一新增是 {@link #conversationByThreadOwned}——把 controller 里的「不存在与异主同判」
 * 收进来，端点只留响应格式。</p>
 *
 * <p>「生成中」标志经 {@link RunStatusGateway} 读 run 表（规则 8：本类不许 import legacy）。</p>
 */
@Service
@RequiredArgsConstructor
public class ConversationQueryService {

    private final AssistantConversationMapper conversationMapper;
    private final AssistantMessageMapper messageMapper;
    private final RunStatusGateway runStatusGateway;

    /**
     * 会话列表（180 天语义由清理 job 保证；前端 E2 数据源）。
     * status 口径（批H P1-1）：ACTIVE=默认只列活跃（抽屉/悬浮球兼容）；ARCHIVED=只列归档
     * （归档折叠条）；ALL=两者都列（会话页本地分组）。del_flag 兜底过滤（本表清理以物理删
     * 为主，软删行不出现）。
     */
    public List<DcsAssistantConversation> listConversations(String tenantId, Long userId,
                                                            int limit, int offset, String status) {
        return conversationMapper.selectList(conversationQuery(tenantId, userId, status)
            // id 决胜（AU-19）：last_message_time 并列（PG DESC NULLS FIRST + 同秒多行）时
            // 排序不稳定，跨页会漂移——前端「加载更多」按页追加会漏行/重行
            .orderByDesc(DcsAssistantConversation::getLastMessageTime)
            .orderByDesc(DcsAssistantConversation::getId)
            .last("LIMIT " + limit + " OFFSET " + offset));
    }

    /** 会话总数（列表分页 total 真值；dsflash P2-6 修——恒 rows.size() 会让翻页失真） */
    public long countConversations(String tenantId, Long userId, String status) {
        Long n = conversationMapper.selectCount(conversationQuery(tenantId, userId, status));
        return n == null ? 0 : n;
    }

    /**
     * 会话列表回填 running 标志（业务批4 Q4，2026-10-12）：按 run 表该用户当前
     * RUNNING 的 thread 集合标"生成中"——刷新/重开后侧栏与列表仍能识别在途轮。
     * 无 RUNNING 行时一行都不改（保持 null，前端按 false 渲染）。
     */
    public void markRunning(List<DcsAssistantConversation> rows, String tenantId, Long userId) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<String> runningThreads = runStatusGateway.runningThreadIds(tenantId, userId);
        if (runningThreads.isEmpty()) {
            return;
        }
        for (DcsAssistantConversation row : rows) {
            row.setRunning(runningThreads.contains(row.getThreadId()));
        }
    }

    /** 按 threadId 取会话（自动化通知深链数据源；不带归属过滤——调用方自判，见下方法） */
    public DcsAssistantConversation conversationByThread(String tenantId, String threadId) {
        return conversationMapper.selectByThread(tenantId, threadId);
    }

    /**
     * 按 threadId 取本人会话（7B 从 {@code AssistantConversationController#byThread} 收进来）：
     * 不存在与异主一律返回 null——不区分，防探测（同 thread 首绑口径）；响应文案由调用方给。
     */
    public DcsAssistantConversation conversationByThreadOwned(String tenantId, Long userId, String threadId) {
        DcsAssistantConversation row = conversationMapper.selectByThread(tenantId, threadId);
        if (row == null || !userId.equals(row.getUserId())) {
            return null;
        }
        return row;
    }

    /** 历史消息（本人会话；E2 会话重建/独立会话页数据源） */
    public List<DcsAssistantMessage> listMessages(String tenantId, Long userId, Long conversationId,
                                                  int limit, int offset) {
        DcsAssistantConversation conv = conversationMapper.selectOwned(tenantId, conversationId, userId);
        if (conv == null) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        return messageMapper.selectPageOwned(tenantId, conversationId, userId, limit, offset);
    }

    /** 尾页消息（最新 N 条，返回前反转回时间序）。批A 二审 P1：压缩后的历史重载/长会话
     *  打开必须取尾部——ASC 首页在 >100 条会话上会把最新回答与压缩分隔条截掉 */
    public List<DcsAssistantMessage> listMessagesTail(String tenantId, Long userId, Long conversationId,
                                                       int limit) {
        DcsAssistantConversation conv = conversationMapper.selectOwned(tenantId, conversationId, userId);
        if (conv == null) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        List<DcsAssistantMessage> rows =
            new ArrayList<>(messageMapper.selectPageOwnedDesc(tenantId, conversationId, userId, limit));
        Collections.reverse(rows);
        return rows;
    }

    private LambdaQueryWrapper<DcsAssistantConversation> conversationQuery(String tenantId, Long userId, String status) {
        String safe = status == null ? "ACTIVE" : status.toUpperCase();
        LambdaQueryWrapper<DcsAssistantConversation> q =
            new LambdaQueryWrapper<DcsAssistantConversation>()
                .eq(DcsAssistantConversation::getTenantId, tenantId)
                .eq(DcsAssistantConversation::getUserId, userId)
                .eq(DcsAssistantConversation::getDelFlag, "0");
        if (!"ALL".equals(safe)) {
            // ALL 之外一律按单值精确过滤（含非法值兜底 ACTIVE，controller 侧已 clamp）
            q.eq(DcsAssistantConversation::getStatus, "ARCHIVED".equals(safe) ? "ARCHIVED" : "ACTIVE");
        }
        return q;
    }
}
