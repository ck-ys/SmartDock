package com.smartdock.assistant.query;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdock.assistant.domain.gateway.RunStatusGateway;
import com.smartdock.assistant.domain.model.AssistantTodoItem;
import com.smartdock.assistant.domain.model.AssistantCompaction;
import com.smartdock.assistant.domain.model.AssistantConversation;
import com.smartdock.assistant.domain.model.AssistantMessage;
import com.smartdock.assistant.mapper.AssistantCompactionMapper;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import com.smartdock.assistant.mapper.AssistantMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationQueryService {

    private final AssistantConversationMapper conversationMapper;
    private final AssistantMessageMapper messageMapper;
    private final AssistantCompactionMapper compactionMapper;
    private final RunStatusGateway runStatusGateway;

    /**
     * 会话列表（180 天语义由清理 job 保证；前端 E2 数据源）。
     * status 口径（批H P1-1）：ACTIVE=默认只列活跃（抽屉/悬浮球兼容）；ARCHIVED=只列归档
     * （归档折叠条）；ALL=两者都列（会话页本地分组）。del_flag 兜底过滤（本表清理以物理删
     * 为主，软删行不出现）。
     */
    public List<AssistantConversation> listConversations(String tenantId, Long userId,
                                                            int limit, int offset, String status) {
        return conversationMapper.selectList(conversationQuery(tenantId, userId, status)
            // id 决胜（AU-19）：last_message_time 并列（PG DESC NULLS FIRST + 同秒多行）时
            // 排序不稳定，跨页会漂移——前端「加载更多」按页追加会漏行/重行
            .orderByDesc(AssistantConversation::getLastMessageTime)
            .orderByDesc(AssistantConversation::getId)
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
    public void markRunning(List<AssistantConversation> rows, String tenantId, Long userId) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<String> runningThreads = runStatusGateway.runningThreadIds(tenantId, userId);
        if (runningThreads.isEmpty()) {
            return;
        }
        for (AssistantConversation row : rows) {
            row.setRunning(runningThreads.contains(row.getThreadId()));
        }
    }

    /** 按 threadId 取会话（自动化通知深链数据源；不带归属过滤——调用方自判，见下方法） */
    public AssistantConversation conversationByThread(String tenantId, String threadId) {
        return conversationMapper.selectByThread(tenantId, threadId);
    }

    /**
     * 按 threadId 取本人会话（7B 从 {@code AssistantConversationController#byThread} 收进来）：
     * 不存在与异主一律返回 null——不区分，防探测（同 thread 首绑口径）；响应文案由调用方给。
     */
    public AssistantConversation conversationByThreadOwned(String tenantId, Long userId, String threadId) {
        AssistantConversation row = conversationMapper.selectByThread(tenantId, threadId);
        // 批 9（2026-11-21，7B 登记 P2-2 修复）：userId 为空（登录态缺位）按无权处理返回 null，
        // 不许 NPE 把 5xx 抛给用户——归属判定 fail-closed
        if (row == null || userId == null || !userId.equals(row.getUserId())) {
            return null;
        }
        return row;
    }

    /** 历史消息（本人会话；E2 会话重建/独立会话页数据源） */
    public List<AssistantMessage> listMessages(String tenantId, Long userId, Long conversationId,
                                                  int limit, int offset) {
        AssistantConversation conv = conversationMapper.selectOwned(tenantId, conversationId, userId);
        if (conv == null) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        return messageMapper.selectPageOwned(tenantId, conversationId, userId, limit, offset);
    }

    /** 尾页消息（最新 N 条，返回前反转回时间序）。批A 二审 P1：压缩后的历史重载/长会话
     *  打开必须取尾部——ASC 首页在 >100 条会话上会把最新回答与压缩分隔条截掉 */
    public List<AssistantMessage> listMessagesTail(String tenantId, Long userId, Long conversationId,
                                                       int limit) {
        AssistantConversation conv = conversationMapper.selectOwned(tenantId, conversationId, userId);
        if (conv == null) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        List<AssistantMessage> rows =
            new ArrayList<>(messageMapper.selectPageOwnedDesc(tenantId, conversationId, userId, limit));
        Collections.reverse(rows);
        return rows;
    }

    /** 本会话最新一条 assistant 消息（批5 Q7-3：前端 done 后拉追问建议）。
     *  批 7C-1（2026-11-20）从 legacy 会话服务搬入——消息写路径已迁 {@code service.MessageWriteService}，
     *  只读不再寄生在写服务上。 */
    public AssistantMessage latestAssistant(String tenantId, Long userId, Long conversationId) {
        return messageMapper.selectLatestAssistant(tenantId, conversationId, userId);
    }

    /** 批B B1 修：环数据源——最新一条 tokens 非空 assistant 行（truncated 空值行不遮蔽旧读数）。
     *  批 7C-1 从 legacy 搬入（同上）。 */
    public Long latestContextTokens(String tenantId, Long userId, Long conversationId) {
        return messageMapper.selectLatestContextTokens(tenantId, conversationId, userId);
    }

    /** 本回合用户提问（批5 Q7-3：assistant 落库前该会话最后一条 user 消息即本回合问题。
     *  七席审查 P1 回修：DESC LIMIT 1 单行单列，替换 ASC 500 行倒扫）。
     *  批 7C-1 从 legacy 搬入（同上）。 */
    public String lastUserQuestion(String tenantId, Long userId, Long conversationId) {
        return messageMapper.selectLatestUserContent(tenantId, conversationId, userId);
    }

    /**
     * 状态卡压缩账单（ckys3 解锁板 题2 案1，2026-11-12）：该会话最近一次成功压缩
     * （前后 token）＋累计次数。只读归并两条查询为一个小记录；无压缩记录返回 count=0
     * 的空账单（前端不渲染行）。口径：只认 SUCCESS 行——FAILED 行上下文虽收缩但摘要
     * 不可信，不上账单（消息流另有「本次没压成」警示条）。
     *
     * <p>批 7C-2 从 legacy 搬入（逐行一致）；写入侧在
     * {@code service.MessageWriteService#appendCompactionRecord}。</p>
     */
    public CompactionBill compactionBill(String tenantId, Long conversationId) {
        int count = compactionMapper.countOkByConversation(tenantId, conversationId);
        if (count == 0) {
            return new CompactionBill(null, null, null, 0);
        }
        AssistantCompaction latest = compactionMapper.selectLatestByConversation(tenantId, conversationId);
        if (latest == null) {
            return new CompactionBill(null, null, null, 0);
        }
        return new CompactionBill(latest.getBeforeTokens(), latest.getAfterTokens(),
            latest.getCreateTime() == null ? null : latest.getCreateTime().getTime(), count);
    }

    /** 账单载荷：beforeTokens/afterTokens=最近一次成功压缩前后 token；atMs=该次时刻；count=累计次数 */
    public record CompactionBill(Integer beforeTokens, Integer afterTokens, Long atMs, int count) {
    }

    /**
     * 当前会话 TODO 清单（前端打开会话恢复分区；无清单返回空数组）。
     *
     * <p>只读会话行的一个 JSON 列（{@code assistant_conversation.todo_items}）；
     * 写入侧在 {@code service.MessageWriteService#saveTodo}。批 7C-2 从 legacy 搬入。</p>
     */
    public List<AssistantTodoItem> todoOf(String tenantId, Long userId, String threadId) {
        AssistantConversation conv = conversationMapper.selectByThread(tenantId, threadId);
        if (conv == null || !conv.getUserId().equals(userId)
            || conv.getTodoItems() == null || conv.getTodoItems().isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(conv.getTodoItems(),
                new TypeReference<List<AssistantTodoItem>>() {
                });
        } catch (Exception e) {
            log.warn("[AssistantTodo] 清单解析失败(按空处理): {}", e.getMessage());
            return List.of();
        }
    }

    /** 清单 JSON 读写（懒初始化，避免每次调用都 new）。
     *
     *  <p><b>与旧实现的唯一差异</b>（多审 glm53 P2-2）：原 {@code AssistantConversationService}
     *  是每次调用 {@code new ObjectMapper()}，这里改成类级 static 共享。{@code ObjectMapper}
     *  线程安全、无状态，共享不改变解析口径与结果，只是少建对象；{@link #todoOf} 的判空顺序、
     *  异常降级（WARN + 返回空表）与旧实现逐行一致。</p> */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LambdaQueryWrapper<AssistantConversation> conversationQuery(String tenantId, Long userId, String status) {
        String safe = status == null ? "ACTIVE" : status.toUpperCase();
        LambdaQueryWrapper<AssistantConversation> q =
            new LambdaQueryWrapper<AssistantConversation>()
                .eq(AssistantConversation::getTenantId, tenantId)
                .eq(AssistantConversation::getUserId, userId)
                .eq(AssistantConversation::getDelFlag, "0");
        if (!"ALL".equals(safe)) {
            // ALL 之外一律按单值精确过滤（含非法值兜底 ACTIVE，controller 侧已 clamp）
            q.eq(AssistantConversation::getStatus, "ARCHIVED".equals(safe) ? "ARCHIVED" : "ACTIVE");
        }
        return q;
    }
}
